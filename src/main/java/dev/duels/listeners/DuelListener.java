package dev.duels.listeners;

import dev.duels.DuelsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

public class DuelListener implements Listener {

    private final DuelsPlugin plugin;

    public DuelListener(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Player killer = dead.getKiller();

        // Party-FFA-Arena Teilnehmer? Eigene Death-Pipeline.
        if (plugin.getPartyFFAManager().isParticipant(dead.getUniqueId())) {
            event.setDeathMessage(null);
            event.getDrops().clear();
            event.setDroppedExp(0);
            event.setKeepInventory(true);
            event.setKeepLevel(true);

            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.getPartyFFAManager().handleDeath(dead));

            // Auto-Respawn
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (dead.isOnline() && dead.isDead()) {
                    try { dead.spigot().respawn(); } catch (Throwable ignored) { dead.spigot().respawn(); }
                }
            }, 2L);

            // Kill-Stat für Killer
            if (killer != null) {
                plugin.getPlayerManager().addStat(killer.getUniqueId(), "kills", 1);
            }
            plugin.getPlayerManager().addStat(dead.getUniqueId(), "deaths", 1);
            return;
        }

        // In Duel?
        if (plugin.getDuelManager().isInDuel(dead.getUniqueId())) {
            event.setDeathMessage(null);
            event.getDrops().clear();
            event.setDroppedExp(0);
            event.setKeepInventory(true);
            event.setKeepLevel(true);

            // Duel Death behandeln (setzt pendingRoundRespawn für den Toten)
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.getDuelManager().handleDuelDeath(dead, killer, false));

            // Auto-Respawn erzwingen, damit der Spieler nicht auf dem
            // "You Died" Screen hängen bleibt.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (dead.isOnline() && dead.isDead()) {
                    try {
                        dead.spigot().respawn();
                    } catch (Throwable ignored) {
                        // Paper fallback
                        dead.spigot().respawn();
                    }
                }
            }, 2L);
            return;
        }

        // Normaler Death - Stats updaten
        plugin.getPlayerManager().addStat(dead.getUniqueId(), "deaths", 1);
        if (killer != null) {
            plugin.getPlayerManager().addStat(killer.getUniqueId(), "kills", 1);
        }

        // Scoreboards updaten
        plugin.getScoreboardManager().updateScoreboard(dead);
        if (killer != null) {
            plugin.getScoreboardManager().updateScoreboard(killer);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();

        // Außerhalb Duel/FFA: kein Damage-Block durch dieses Plugin mehr.
        // User regelt PvP/PvE in der Lobby über andere Plugins.
        // (Plugin sorgt nur dafür, dass keine externen Plugins den Spieler
        // im Duel/FFA sterben lassen wenn sie es nicht sollten — das wird
        // durch die separaten Damage-By-Entity-Hooks unten gehandhabt.)
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();

        // In Duel - Schaden erlauben
        if (plugin.getDuelManager().isInDuel(player.getUniqueId())) {
            return;
        }

        // Party-FFA: PvP nur zwischen Mitgliedern derselben Session zulassen.
        // Andere Damager (z.B. zufälliger Lobby-Spieler, der über das FFA-
        // Areal läuft) werden geblockt.
        if (plugin.getPartyFFAManager().isParticipant(player.getUniqueId())) {
            if (event.getDamager() instanceof Player damager) {
                if (plugin.getPartyFFAManager().canDamage(damager.getUniqueId(), player.getUniqueId())) {
                    return;
                }
                event.setCancelled(true);
                return;
            }
            // Umweltschaden (Void/Fall/Fire) im FFA durchlassen
            return;
        }

        // Außerhalb Duel/FFA: Plugin blockt nichts mehr (User regelt PvP /
        // Diamond-Sword-Shortcut über andere Plugins). Standard-Vanilla.
    }

    // Drop ist global erlaubt. Wenn ein Anti-Grief-Plugin das Event cancelt
    // (User-Bug: "im duel kann man nicht droppen") un-canceln wir es für
    // Spieler im Duel/FFA. Außerhalb Duel/FFA bleibt der Event-Status wie
    // die anderen Plugins ihn gesetzt haben.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        boolean inDuel = plugin.getDuelManager() != null
                && plugin.getDuelManager().isInDuel(player.getUniqueId());
        boolean inFFA = plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(player.getUniqueId());
        if (!inDuel && !inFFA) return;
        // Hotbar-Lock-Item? Dann NICHT erlauben (theoretisch nie der Fall im
        // Duel, aber sicher ist sicher).
        if (plugin.getHotbarManager() != null) {
            String action = plugin.getHotbarManager()
                    .readAction(event.getItemDrop().getItemStack());
            if (action != null && !action.isEmpty()) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.isCancelled()) event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDropItem(EntityDropItemEvent event) {
        // Drop global erlaubt (User-Wunsch). Falls Anti-Grief gecancelt hat
        // und der Spieler im Duel/FFA ist, un-canceln.
        if (event.getEntity() instanceof Player player) {
            boolean inDuel = plugin.getDuelManager().isInDuel(player.getUniqueId());
            boolean inFFA = plugin.getPartyFFAManager().isParticipant(player.getUniqueId());
            if ((inDuel || inFFA) && event.isCancelled()) {
                event.setCancelled(false);
            }
        }
    }

    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.getPrefix() + "§cBeds are disabled! Spawn is fixed.");
    }
}