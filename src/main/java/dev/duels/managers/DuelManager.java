package dev.duels.managers;

import dev.duels.DuelsPlugin;
import dev.duels.objects.Arena;
import dev.duels.objects.DuelRequest;
import dev.duels.objects.DuelSession;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class DuelManager {

    private final DuelsPlugin plugin;

    private final Map<UUID, DuelSession> activeDuels = new HashMap<>();
    private final List<DuelSession> sessions = new ArrayList<>();

    // Player Backup (Inventory + State)
    private final Map<UUID, PlayerState> savedStates = new HashMap<>();

    public DuelManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------
    // START DUEL
    // -------------------------
    public void startDuel(DuelRequest request) {

        Player p1 = Bukkit.getPlayer(request.getPlayer1());
        Player p2 = Bukkit.getPlayer(request.getPlayer2());

        if (p1 == null || p2 == null) return;

        Arena arena = plugin.getArenaManager().getArena(request.getArenaName());
        if (arena == null) return;

        arena.setInUse(true);

        // Save player state
        saveState(p1);
        saveState(p2);

        // Create session
        DuelSession session = new DuelSession(
                p1.getUniqueId(),
                p2.getUniqueId(),
                request.getKitName(),
                arena.getName(),
                request.getBestOf()
        );

        activeDuels.put(p1.getUniqueId(), session);
        activeDuels.put(p2.getUniqueId(), session);
        sessions.add(session);

        // Setup players
        setupDuelPlayer(p1);
        setupDuelPlayer(p2);

        // Teleport
        teleportToArena(p1, arena.getSpawn1());
        teleportToArena(p2, arena.getSpawn2());

        // Visibility
        plugin.getPlayerManager().applyDuelVisibility(p1, p2);

        p1.sendMessage(plugin.getPrefix() + "§aDuel started!");
        p2.sendMessage(plugin.getPrefix() + "§aDuel started!");
    }

    // -------------------------
    // END DUEL
    // -------------------------
    public void endDuel(Player winner, Player loser) {

        if (winner == null || loser == null) return;

        DuelSession session = activeDuels.get(winner.getUniqueId());
        if (session == null) session = activeDuels.get(loser.getUniqueId());

        if (session == null) return;

        Arena arena = plugin.getArenaManager().getArena(session.getArenaName());
        if (arena != null) {
            arena.setInUse(false);
        }

        UUID p1 = winner.getUniqueId();
        UUID p2 = loser.getUniqueId();

        activeDuels.remove(p1);
        activeDuels.remove(p2);

        // Stats
        plugin.getPlayerManager().addStat(p1, "wins", 1);
        plugin.getPlayerManager().addStat(p2, "losses", 1);

        // Reset arena
        if (arena != null) {
            plugin.getArenaManager().resetArena(arena, () -> {});
        }

        // Restore players
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            restoreState(winner);
            restoreState(loser);

            plugin.getPlayerManager().teleportToSpawn(winner);
            plugin.getPlayerManager().teleportToSpawn(loser);

            plugin.getPlayerManager().restoreAllVisibility();
        }, 10L);
    }

    // -------------------------
    // FORCE END (disconnect etc.)
    // -------------------------
    public void forceEnd(UUID uuid) {

        DuelSession session = activeDuels.get(uuid);
        if (session == null) return;

        Player p1 = Bukkit.getPlayer(session.getPlayer1());
        Player p2 = Bukkit.getPlayer(session.getPlayer2());

        if (p1 != null && p2 != null) {
            Player winner = p1.getUniqueId().equals(uuid) ? p2 : p1;
            Player loser = winner == p1 ? p2 : p1;
            endDuel(winner, loser);
        }
    }

    // -------------------------
    // STATE HANDLING
    // -------------------------
    private void saveState(Player p) {
        savedStates.put(p.getUniqueId(), new PlayerState(p));
    }

    private void restoreState(Player p) {
        if (p == null) return;

        PlayerState state = savedStates.remove(p.getUniqueId());
        if (state != null) {
            state.restore(p);
        }
    }

    private void setupDuelPlayer(Player p) {
        p.getInventory().clear();
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setAllowFlight(false);
        p.setFlying(false);
    }

    private void teleportToArena(Player p, Location loc) {
        if (p == null || loc == null) return;
        p.teleport(loc);
    }

    // -------------------------
    // CHECKS
    // -------------------------
    public boolean isInDuel(UUID uuid) {
        return activeDuels.containsKey(uuid);
    }

    public DuelSession getSession(UUID uuid) {
        return activeDuels.get(uuid);
    }

    public List<DuelSession> getAllSessions() {
        return sessions;
    }

    // -------------------------
    // INNER CLASS
    // -------------------------
    private static class PlayerState {

        private final ItemStack[] inventory;
        private final ItemStack[] armor;
        private final Location location;
        private final double health;
        private final int food;

        public PlayerState(Player p) {
            this.inventory = p.getInventory().getContents();
            this.armor = p.getInventory().getArmorContents();
            this.location = p.getLocation().clone();
            this.health = p.getHealth();
            this.food = p.getFoodLevel();
        }

        public void restore(Player p) {
            p.getInventory().setContents(inventory);
            p.getInventory().setArmorContents(armor);
            p.teleport(location);
            p.setHealth(health);
            p.setFoodLevel(food);
        }
    }
}
