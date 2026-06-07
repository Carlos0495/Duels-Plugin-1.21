package dev.duels.listeners;

import dev.duels.DuelsPlugin;
import dev.duels.objects.Arena;
import dev.duels.objects.BlockVector;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

public class ArenaListener implements Listener {

    private final DuelsPlugin plugin;

    public ArenaListener(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        // Tracking für Duel UND FFA/Team-Match (sonst bleibt placed-Tracking
        // bei Team-Match unvollständig).
        boolean inDuel = plugin.getDuelManager().isInDuel(player.getUniqueId());
        boolean inFFA  = plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(player.getUniqueId());
        if (!inDuel && !inFFA) return;

        Location blockLoc = event.getBlock().getLocation();
        Arena arena = resolveArenaForPlayer(player, blockLoc);
        if (arena == null) return;

        BlockVector vector = new BlockVector(
                blockLoc.getBlockX(), blockLoc.getBlockY(), blockLoc.getBlockZ());
        if (arena.isPlayerPlacedBlock(vector)) {
            // Spieler bricht einen vorher von ihm platzierten Block ab.
            // Aus dem Placed-Tracking entfernen — Reset muss NICHTS tun.
            arena.removePlayerPlacedBlock(vector);
        } else {
            // ORIGINAL-Welt-Block wird gebrochen → für Reset speichern,
            // damit das Loch beim Match-Ende wieder geschlossen wird.
            // Auch dann wenn die Arena keinen Snapshot hat.
            if (!arena.getOriginalBlocks().containsKey(vector)) {
                try { arena.getOriginalBlocks().put(vector,
                        event.getBlock().getBlockData().clone()); }
                catch (Throwable ignored) {}
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        // Tracking für Duel UND FFA/Team-Match (sonst bleiben Blöcke von
        // Team-Spielern beim Reset stehen).
        boolean inDuel = plugin.getDuelManager().isInDuel(player.getUniqueId());
        boolean inFFA  = plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(player.getUniqueId());
        if (!inDuel && !inFFA) return;

        Location blockLoc = event.getBlock().getLocation();
        Arena arena = resolveArenaForPlayer(player, blockLoc);
        if (arena == null) return;

        BlockVector vector = new BlockVector(
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
        arena.addPlayerPlacedBlock(vector);
    }

    /**
     * Wasser/Lava per Eimer platzieren erzeugt KEIN BlockPlaceEvent, sondern
     * ein PlayerBucketEmptyEvent — die Flüssigkeits-Quelle wurde dadurch bisher
     * nie als player-placed getrackt und blieb beim Arena-Reset stehen (bzw.
     * floss nach dem Reset wieder nach → "flaches Wasser"). Hier tracken wir
     * den Quell-Block, damit der Reset ihn zu Luft zurücksetzt.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        boolean inDuel = plugin.getDuelManager().isInDuel(player.getUniqueId());
        boolean inFFA  = plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(player.getUniqueId());
        if (!inDuel && !inFFA) return;

        Block b = event.getBlock();
        if (b == null) return;
        Location loc = b.getLocation();
        Arena arena = resolveArenaForPlayer(player, loc);
        if (arena == null) return;

        BlockVector v = new BlockVector(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        // Nur tracken wenn der Original-Block hier nicht festgelegt ist
        // (sonst übernimmt der Snapshot-Restore den korrekten Originalzustand).
        if (!arena.getOriginalBlocks().containsKey(v)) {
            arena.addPlayerPlacedBlock(v);
        }
    }

    /**
     * Schöpft ein Spieler eine ORIGINAL-Flüssigkeit (z.B. einen legitimen
     * Wasser-See der Arena) mit dem Eimer ab, würde sie beim Reset fehlen.
     * Wir merken uns daher den Originalzustand, damit der Reset ihn
     * wiederherstellt.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        boolean inDuel = plugin.getDuelManager().isInDuel(player.getUniqueId());
        boolean inFFA  = plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(player.getUniqueId());
        if (!inDuel && !inFFA) return;

        Block b = event.getBlock();
        if (b == null) return;
        Location loc = b.getLocation();
        Arena arena = resolveArenaForPlayer(player, loc);
        if (arena == null) return;

        BlockVector v = new BlockVector(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (arena.isPlayerPlacedBlock(v)) {
            // War vom Spieler platzierte Flüssigkeit → einfach aus dem
            // Tracking nehmen, Reset muss nichts tun.
            arena.removePlayerPlacedBlock(v);
        } else if (!arena.getOriginalBlocks().containsKey(v)) {
            try { arena.getOriginalBlocks().put(v, b.getBlockData().clone()); }
            catch (Throwable ignored) {}
        }
    }

    /**
     * Resolve die Arena für einen Duel-Spieler. Bevorzugt:
     * 1. Arena der aktiven Duel-Session (per Name) — funktioniert auch ohne Corners.
     * 2. Fallback: räumlich per {@code getArenaAt}.
     */
    private Arena resolveArenaForPlayer(Player player, Location loc) {
        var session = plugin.getDuelManager().getDuelSession(player.getUniqueId());
        if (session != null && session.getArenaName() != null) {
            Arena byName = plugin.getArenaManager().getArena(session.getArenaName());
            if (byName != null) return byName;
        }
        if (plugin.getPartyFFAManager() != null) {
            var ffa = plugin.getPartyFFAManager().getSession(player.getUniqueId());
            if (ffa != null && ffa.reservedArena != null) return ffa.reservedArena;
        }
        return plugin.getArenaManager().getArenaAt(loc);
    }

    // Explosionen werden komplett von BlockBreakListener.onEntityExplode /
    // onBlockExplode (HIGHEST + Kit-Whitelist-Filter) gehandhabt. Die alten
    // Handler hier filterten zu "nur player-placed Blöcke" UND liefen vor
    // dem Whitelist-Listener — dadurch konnten Crystals selbst dann keine
    // Whitelist-Blöcke zerstören, wenn der Admin sie in breakable-blocks
    // freigegeben hatte. Daher entfernt.

    // Block-Drops sind in Arenen erlaubt (User-Wunsch: "wenn man es breakt
    // dann dropped der block nicht" — alte Logik hat ALLE BlockDropItem-
    // Events in aktiven Arenen gecancelt, was sowohl Block-Break-Drops als
    // auch normale Drops gekillt hat). Wenn ein Anti-Grief-Plugin gecancelt
    // hat und der Block player-placed war, un-canceln wir.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockDrop(BlockDropItemEvent event) {
        Location loc = event.getBlock().getLocation();
        Arena arena = plugin.getArenaManager().getArenaAt(loc);
        if (arena == null || !arena.isInUse()) return;
        if (event.isCancelled()) event.setCancelled(false);
    }

    // Item-Drops in Arenen sind explizit erlaubt (User-Wunsch: "im duel kann
    // man nicht droppen aber man soll es können"). Alte Logik cancelte
    // jeden ItemSpawn in aktiven Arenen. Jetzt: NICHTS canceln — gedroppte
    // Items werden beim Arena-Reset (cleanupArenaEntities) entfernt.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onItemSpawn(ItemSpawnEvent event) {
        Location loc = event.getLocation();
        Arena arena = plugin.getArenaManager().getArenaAt(loc);
        if (arena == null || !arena.isInUse()) return;
        if (event.isCancelled()) event.setCancelled(false);
    }

    @EventHandler
    public void onBlockFromTo(BlockFromToEvent event) {
        Location from = event.getBlock().getLocation();
        Location to = event.getToBlock().getLocation();

        Arena aFrom = plugin.getArenaManager().getArenaAt(from);
        Arena aTo = plugin.getArenaManager().getArenaAt(to);

        boolean fromInUse = aFrom != null && aFrom.isInUse() && aFrom.isInArena(from);
        boolean toInUse = aTo != null && aTo.isInUse() && aTo.isInArena(to);

        if (fromInUse && toInUse) return;

        if (fromInUse || toInUse) {
            event.setCancelled(true);
        }
    }


    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        Location loc = event.getBlock().getLocation();
        Arena arena = plugin.getArenaManager().getArenaAt(loc);

        if (arena != null && arena.isInUse()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBurn(BlockBurnEvent event) {
        Location loc = event.getBlock().getLocation();
        Arena arena = plugin.getArenaManager().getArenaAt(loc);

        if (arena != null && arena.isInUse()) {
            event.setCancelled(true);
        }
    }


    @EventHandler
    public void onBlockFade(BlockFadeEvent event) {
        Location loc = event.getBlock().getLocation();
        Arena arena = plugin.getArenaManager().getArenaAt(loc);

        if (arena != null && arena.isInUse()) {
            event.setCancelled(true);
        }
    }

    /**
     * Tracking für Blöcke, die durch Fluid-Interaktion entstehen
     * (Wasser+Lava → Obsidian/Cobble/Stone). User-Wunsch: solche
     * Block-Formationen sollen beim Arena-Reset auch wieder verschwinden.
     */
    @EventHandler
    public void onBlockForm(BlockFormEvent event) {
        Location loc = event.getBlock().getLocation();
        Arena arena = findActiveArenaNear(loc);
        if (arena == null) return;
        BlockVector v = new BlockVector(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        arena.addPlayerPlacedBlock(v);
    }

    @EventHandler
    public void onBlockFromTo2(org.bukkit.event.block.BlockFromToEvent event) {
        // BlockFromTo wird oben schon gehandelt (cancel cross-boundary).
        // Hier nur tracken, falls Lava in einer aktiven Arena fließt und
        // einen neuen Block bildet — wir markieren das Ziel als
        // player-placed damit Reset es entfernt.
        if (event.isCancelled()) return;
        Location to = event.getToBlock().getLocation();
        Arena arena = findActiveArenaNear(to);
        if (arena == null) return;
        // Tracken wenn der Ziel-Block leer ODER bereits Flüssigkeit war
        // (fließendes Wasser/Lava breitet sich über mehrere Level aus — auch
        // diese Ziel-Blöcke müssen beim Reset entfernt werden, sonst bleibt
        // "flaches Wasser" stehen).
        org.bukkit.Material toMat = event.getToBlock().getType();
        BlockVector v = new BlockVector(to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if ((toMat.isAir() || toMat == org.bukkit.Material.WATER || toMat == org.bukkit.Material.LAVA)
                && !arena.getOriginalBlocks().containsKey(v)) {
            arena.addPlayerPlacedBlock(v);
        }
    }

    /**
     * End-Crystals (und andere placeable Entities wie Armor Stands) werden
     * über EntityPlaceEvent gespawnt — NICHT BlockPlaceEvent. Wir prüfen
     * die Kit-Whitelist (placeable-blocks enthält das Item-Material?) und
     * tracken die Entity für den Arena-Reset.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPlace(org.bukkit.event.entity.EntityPlaceEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        if (player == null) return;
        if (!plugin.getDuelManager().isInDuel(player.getUniqueId())
                && !plugin.getPartyFFAManager().isParticipant(player.getUniqueId())) return;

        String kitId = currentKitId(player);
        if (kitId == null) return;
        dev.duels.managers.KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) { event.setCancelled(true); return; }

        // EnderCrystal/Boat/ArmorStand etc.: Material des Items aus der Hand
        // bestimmen und gegen Kit-Whitelist prüfen.
        org.bukkit.Material handMat = null;
        if (player.getInventory().getItemInMainHand() != null) {
            handMat = player.getInventory().getItemInMainHand().getType();
        }
        if (handMat == null || handMat.isAir()) return;

        if (kit.isPlaceable(handMat)) {
            if (event.isCancelled()) event.setCancelled(false);
            // Entity tracken: bei Arena-Reset entfernt cleanupArenaEntities()
            // alles in der Bounding-Box. Falls Corners fehlen, brauchen wir
            // die UUID — daher zusätzlich an die Arena hängen.
            Arena arena = resolveArenaForPlayer(player);
            if (arena != null && event.getEntity() != null) {
                arena.addTrackedEntity(event.getEntity().getUniqueId());
            }
        } else {
            event.setCancelled(true);
        }
    }

    private String currentKitId(org.bukkit.entity.Player p) {
        var ds = plugin.getDuelManager().getDuelSession(p.getUniqueId());
        if (ds != null) return ds.getKitName();
        var ffa = plugin.getPartyFFAManager().getSession(p.getUniqueId());
        if (ffa != null) return ffa.kitName;
        return null;
    }

    private Arena resolveArenaForPlayer(org.bukkit.entity.Player p) {
        var ds = plugin.getDuelManager().getDuelSession(p.getUniqueId());
        if (ds != null && ds.getArenaName() != null) {
            Arena a = plugin.getArenaManager().getArena(ds.getArenaName());
            if (a != null) return a;
        }
        var ffa = plugin.getPartyFFAManager().getSession(p.getUniqueId());
        if (ffa != null && ffa.reservedArena != null) {
            return ffa.reservedArena;
        }
        return plugin.getArenaManager().getArenaAt(p.getLocation());
    }

    /**
     * Findet eine aktive Arena (inUse), in der die Location grob liegt —
     * primär spatial via getArenaAt, mit Fallback auf die nächstgelegene
     * aktive Arena in derselben Welt (für Form-Events nahe Spieler ohne
     * Corner-Bounds-Match).
     */
    private Arena findActiveArenaNear(Location loc) {
        Arena spatial = plugin.getArenaManager().getArenaAt(loc);
        if (spatial != null && spatial.isInUse()) return spatial;
        // Fallback: kürzeste Distanz zu spawn1 einer in-use Arena in der
        // gleichen Welt.
        Arena best = null;
        double bestDist = Double.MAX_VALUE;
        for (Arena a : plugin.getArenaManager().getAllArenas()) {
            if (!a.isInUse()) continue;
            if (a.getSpawn1() == null) continue;
            if (a.getSpawn1().getWorld() == null) continue;
            if (!a.getSpawn1().getWorld().equals(loc.getWorld())) continue;
            double d = a.getSpawn1().distanceSquared(loc);
            if (d < bestDist && d < 200 * 200) {
                bestDist = d;
                best = a;
            }
        }
        return best;
    }
}