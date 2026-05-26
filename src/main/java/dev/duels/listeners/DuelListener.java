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
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

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

            // Kill-Stat für Killer (nicht bei Selbsttötung)
            if (killer != null && !killer.equals(dead)) {
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

        // Normaler Death: Items nicht droppen (Inventar wird beim Respawn
        // ohnehin komplett neu aufgesetzt). Death-Message NICHT ändern
        // damit externe Plugins (z.B. DeathMessages) sie anpassen können.
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        event.setKeepLevel(true);

        plugin.getPlayerManager().addStat(dead.getUniqueId(), "deaths", 1);
        if (killer != null && !killer.equals(dead)) {
            plugin.getPlayerManager().addStat(killer.getUniqueId(), "kills", 1);
        }

        // Auto-Respawn + State-Reset. UUID-basiert damit wir nach dem
        // Delay ein frisches Player-Objekt haben (kein stale reference).
        final UUID deadId = dead.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(deadId);
            if (p == null || !p.isOnline()) return;
            if (p.isDead()) {
                try { p.spigot().respawn(); } catch (Throwable ignored) {}
            }
        }, 2L);
        // Nach dem Respawn: Teleport + voller State-Reset
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(deadId);
            if (p == null || !p.isOnline()) return;
            if (plugin.getDuelManager().isInDuel(deadId)) return;
            org.bukkit.Location spawn = plugin.getArenaManager().getSpawnLocation();
            if (spawn != null) {
                p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                p.setFallDistance(0f);
                p.teleport(spawn);
            }
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            p.setHealth(p.getAttribute(
                    org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.setFireTicks(0);
            p.getInventory().clear();
            plugin.getPlayerManager().setupPlayerInventory(p);
            p.updateInventory();
            plugin.getPlayerManager().applyLobbyFly(p);
            plugin.getScoreboardManager().updateScoreboard(p);
            // Entity für alle Observer refreshen (Ghost-State fix)
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(p)) continue;
                other.hidePlayer(plugin, p);
                other.showPlayer(plugin, p);
            }
        }, 5L);
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

    // MONITOR-Override: wenn nach unserem HIGHEST-Un-Cancel ein noch
    // späterer HIGHEST/MONITOR-Listener das Drop-Event wieder cancelt,
    // legen wir das Item im nächsten Tick manuell ab (Anti-Grief-Bypass).
    // User-Bug: "droppen geht immernoch nicht".
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onItemDropMonitor(PlayerDropItemEvent event) {
        if (!event.isCancelled()) return;
        final Player player = event.getPlayer();
        if (player == null) return;
        boolean inDuel = plugin.getDuelManager() != null
                && plugin.getDuelManager().isInDuel(player.getUniqueId());
        boolean inFFA = plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(player.getUniqueId());
        if (!inDuel && !inFFA) return;
        // Hotbar-Lock-Item nicht manuell droppen (defense-in-depth).
        if (plugin.getHotbarManager() != null) {
            String action = plugin.getHotbarManager()
                    .readAction(event.getItemDrop().getItemStack());
            if (action != null && !action.isEmpty()) return;
        }
        final org.bukkit.inventory.ItemStack stack =
                event.getItemDrop().getItemStack().clone();
        final org.bukkit.Location loc = player.getEyeLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            // Slot leeren — bei Drop wurde das Item aus dem Inv genommen,
            // bei Cancel zurückgegeben. Da wir manuell droppen, müssen wir
            // den passenden Slot entfernen.
            var inv = player.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                var it = inv.getItem(i);
                if (it != null && it.isSimilar(stack)) {
                    if (it.getAmount() <= stack.getAmount()) {
                        inv.setItem(i, null);
                    } else {
                        it.setAmount(it.getAmount() - stack.getAmount());
                        inv.setItem(i, it);
                    }
                    break;
                }
            }
            org.bukkit.entity.Item dropped = player.getWorld().dropItem(loc, stack);
            dropped.setVelocity(player.getLocation().getDirection().multiply(0.3));
            dropped.setPickupDelay(40);
        });
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

    /**
     * Ender-Pearl-Teleport für Spieler außerhalb von Duel/FFA canceln.
     * Der Pearl-TP passiert VOR dem Schaden — wenn der Schaden dann
     * tötet, entsteht ein Ghost-State weil Client und Server über die
     * Position desynct sind. Cancel des TPs verhindert auch den Schaden.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            Player p = event.getPlayer();
            UUID uuid = p.getUniqueId();
            if (!plugin.getDuelManager().isInDuel(uuid)
                    && !plugin.getPartyFFAManager().isParticipant(uuid)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.getPrefix() + "§cBeds are disabled! Spawn is fixed.");
    }
}