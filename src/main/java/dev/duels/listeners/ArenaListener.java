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
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;

public class ArenaListener implements Listener {

    private final DuelsPlugin plugin;

    public ArenaListener(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        // Im Duel? Nur dann interessieren wir uns für Tracking.
        // Block-Cancel entscheidet ALLEIN der BlockBreakListener
        // (Kit-Whitelist). Wir NICHT — auch wenn der Spieler außerhalb
        // der Corners ist (große/keine Corners-Arenen sonst broken).
        if (!plugin.getDuelManager().isInDuel(player.getUniqueId())) return;

        Location blockLoc = event.getBlock().getLocation();
        Arena arena = resolveArenaForPlayer(player, blockLoc);
        if (arena == null) return;

        BlockVector vector = new BlockVector(
                blockLoc.getBlockX(), blockLoc.getBlockY(), blockLoc.getBlockZ());
        if (arena.isPlayerPlacedBlock(vector)) {
            arena.removePlayerPlacedBlock(vector);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        // Im Duel? Tracking, aber NIE canceln (Whitelist entscheidet im
        // BlockBreakListener). Auch wenn keine Corners gesetzt sind, das
        // Place ist erlaubt.
        if (!plugin.getDuelManager().isInDuel(player.getUniqueId())) return;

        Location blockLoc = event.getBlock().getLocation();
        Arena arena = resolveArenaForPlayer(player, blockLoc);
        if (arena == null) return;

        BlockVector vector = new BlockVector(
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
        arena.addPlayerPlacedBlock(vector);
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
        return plugin.getArenaManager().getArenaAt(loc);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        Location loc = event.getBlock().getLocation();
        Arena arena = plugin.getArenaManager().getArenaAt(loc);

        if (arena != null && arena.isInUse()) {
            // Nur player-placed blocks explodieren lassen
            event.blockList().removeIf(block -> {
                BlockVector v = new BlockVector(block.getX(), block.getY(), block.getZ());
                return !arena.isPlayerPlacedBlock(v);
            });

            // Player-placed blocks aus Tracking entfernen
            for (Block block : event.blockList()) {
                BlockVector v = new BlockVector(block.getX(), block.getY(), block.getZ());
                arena.removePlayerPlacedBlock(v);
            }
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        Location loc = event.getLocation();
        Arena arena = plugin.getArenaManager().getArenaAt(loc);

        if (arena != null && arena.isInUse()) {
            // Nur player-placed blocks explodieren lassen
            event.blockList().removeIf(block -> {
                BlockVector v = new BlockVector(block.getX(), block.getY(), block.getZ());
                return !arena.isPlayerPlacedBlock(v);
            });

            // Player-placed blocks aus Tracking entfernen
            for (Block block : event.blockList()) {
                BlockVector v = new BlockVector(block.getX(), block.getY(), block.getZ());
                arena.removePlayerPlacedBlock(v);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        Location loc = event.getBlock().getLocation();
        Arena arena = plugin.getArenaManager().getArenaAt(loc);

        if (arena != null && arena.isInUse()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Location loc = event.getLocation();
        Arena arena = plugin.getArenaManager().getArenaAt(loc);

        if (arena != null && arena.isInUse()) {
            event.setCancelled(true);
        }
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
}