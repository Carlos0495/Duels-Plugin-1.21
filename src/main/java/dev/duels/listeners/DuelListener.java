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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class DuelListener implements Listener {

    private final DuelsPlugin plugin;
    /** Spieler die gerade per Ender-Pearl teleportiert wurden (außerhalb Duel/FFA). */
    private final Set<UUID> recentPearlTP = new HashSet<>();

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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // Ender-Pearl tödlicher Schaden außerhalb Duel/FFA:
        // Pearl-Schaden canceln, Spieler erst zum Spawn teleportieren,
        // dann dort töten. So passiert der Tod in der Spawn-Welt
        // (kein Cross-World-Desync / Ghost-State) und die Death-Message
        // vom DeathMessages-Plugin kommt normal durch.
        UUID uuid = player.getUniqueId();
        if (recentPearlTP.contains(uuid)
                && player.getHealth() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            recentPearlTP.remove(uuid);

            // Erst zum Spawn TP, dann dort sterben lassen
            org.bukkit.Location spawn = plugin.getArenaManager().getSpawnLocation();
            if (spawn != null) {
                player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                player.setFallDistance(0f);
                player.teleport(spawn);
            }
            // 1 Tick warten damit der TP verarbeitet ist, dann killen
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                player.setHealth(0);
            }, 1L);
        }
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
        // Custom-Kit-Items dürfen NIEMALS gedroppt werden (Safety).
        if (plugin.getKitManager() != null
                && plugin.getKitManager().isNoDrop(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            return;
        }
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
        // Custom-Kit-Items niemals manuell nachdroppen (Safety).
        if (plugin.getKitManager() != null
                && plugin.getKitManager().isNoDrop(event.getItemDrop().getItemStack())) {
            return;
        }
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
     * Ender-Pearl-TP tracken: Spieler außerhalb Duel/FFA merken damit
     * wir im EntityDamageEvent den Pearl-Schaden abfangen können.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            Player p = event.getPlayer();
            UUID uuid = p.getUniqueId();
            if (!plugin.getDuelManager().isInDuel(uuid)
                    && !plugin.getPartyFFAManager().isParticipant(uuid)) {
                recentPearlTP.add(uuid);
                // Nach 2 Ticks wieder entfernen (Pearl-Schaden kommt
                // im selben oder nächsten Tick nach dem TP)
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> recentPearlTP.remove(uuid), 2L);
            }
        }
    }

    /**
     * Anti-Glitch: verhindert dass Spieler sich per Ender-Pearl durch
     * konfigurierte Blöcke (z.B. Wände/Barrier) glitchen. Läuft auf HIGH
     * (cancelbar). Wenn der Pearl-Pfad durch einen geblockten Block geht,
     * wird der Teleport abgebrochen und der Spieler ein Stück zurückgestoßen.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAntiGlitchTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        if (!plugin.getConfigManager().isAntiGlitchEnabled()) return;

        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        // Scope prüfen: DUELS = nur in Duel/FFA, GLOBAL = überall.
        String scope = plugin.getConfigManager().getAntiGlitchScope();
        boolean inMatch = plugin.getDuelManager().isInDuel(uuid)
                || plugin.getPartyFFAManager().isParticipant(uuid);
        if (!"GLOBAL".equalsIgnoreCase(scope) && !inMatch) return;

        final org.bukkit.Location from = event.getFrom();
        org.bukkit.Location to = event.getTo();
        if (from == null || to == null || from.getWorld() == null
                || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        // Fall 1: Der Pearl-Pfad geht HORIZONTAL durch einen geblockten Block
        // auf Ziel-Höhe (diagonaler/seitlicher Wurf durch die Wand). Schon zum
        // Event-Zeitpunkt erkennbar -> Teleport abbrechen und zurückstoßen.
        if (crossesWallToOtherSide(from, to)) {
            event.setCancelled(true);
            org.bukkit.util.Vector back = from.toVector().subtract(to.toVector());
            back.setY(0);
            if (back.lengthSquared() > 0.0001) {
                back.normalize().multiply(0.4).setY(0.2);
                p.setVelocity(back);
            }
            return;
        }
        // Fall 2: An die Wand ran-tpn ist ERLAUBT, ebenso ein Wurf NACH OBEN auf
        // einen höher gelegenen Boden (User-Wunsch: hochwerfen + oben landen
        // soll teleportieren!). Wer aber vor einer Wand eine Pearl gerade nach
        // unten wirft, wird von Minecraft durch die Wand geschoben (Ejection) —
        // das passiert erst NACH dem Teleport. Deshalb prüfen wir 1 Tick später
        // die tatsächliche Position: nur wenn der Spieler auf der ANDEREN Seite
        // einer Wand gelandet ist (horizontaler Pfad auf seiner End-Höhe kreuzt
        // einen geblockten Block), wird er zurückgesetzt. Ein Hochteleport
        // landet auf/über der Wand -> kreuzt auf End-Höhe nichts -> erlaubt.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            org.bukkit.Location now = p.getLocation();
            if (now.getWorld() == null || !now.getWorld().equals(from.getWorld())) return;
            if (crossesWallToOtherSide(from, now)) {
                p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                p.teleport(from);
            }
        }, 1L);
    }

    /**
     * Prüft ob der Spieler von {@code from} nach {@code dest} durch eine Wand
     * auf die ANDERE Seite gelangt ist. Es wird nur die HORIZONTALE Strecke auf
     * der Ziel-Höhe (Füße + Kopf von {@code dest}) abgetastet — liegt dort ein
     * laut Anti-Glitch-Config geblockter Block zwischen Start und Ziel, ist der
     * Spieler durch die Wand geglitcht.
     *
     * <p>Wichtig: rein vertikale Bewegung (Pearl hoch/runter, gleiche XZ) wird
     * NICHT als Glitch gewertet. Und ein Teleport hoch auf/über eine Wand prüft
     * auf der (höheren) End-Höhe, wo keine Wand mehr zwischen Start und Ziel
     * liegt — bleibt also erlaubt.</p>
     */
    private boolean crossesWallToOtherSide(org.bukkit.Location from, org.bukkit.Location dest) {
        if (from == null || dest == null || dest.getWorld() == null) return false;
        org.bukkit.World world = dest.getWorld();
        double dx = dest.getX() - from.getX();
        double dz = dest.getZ() - from.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        // Keine (nennenswerte) horizontale Bewegung -> keine Wand-Durchquerung.
        if (horiz < 0.30) return false;
        double nx = dx / horiz;
        double nz = dz / horiz;
        int footY = dest.getBlockY();
        int headY = footY + 1;
        double step = 0.25;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        // Etwas vor dem Ziel aufhören, damit der Block AM Ziel selbst (Boden/
        // Wand an die man ran-tpt) nicht fälschlich als "durchquert" zählt.
        for (double d = 0; d <= horiz; d += step) {
            double px = from.getX() + nx * d;
            double pz = from.getZ() + nz * d;
            int bx = org.bukkit.Location.locToBlock(px);
            int bz = org.bukkit.Location.locToBlock(pz);
            long key = (((long) bx & 0x3FFFFFF) << 26) | ((long) bz & 0x3FFFFFF);
            if (!seen.add(key)) continue;
            org.bukkit.Material feet = world.getBlockAt(bx, footY, bz).getType();
            org.bukkit.Material head = world.getBlockAt(bx, headY, bz).getType();
            if (plugin.getConfigManager().isAntiGlitchBlocked(feet)
                    || plugin.getConfigManager().isAntiGlitchBlocked(head)) {
                return true;
            }
        }
        return false;
    }


    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.getConfigManager().prefixed("general.beds-disabled", "&cBeds are disabled! Spawn is fixed."));
    }
}