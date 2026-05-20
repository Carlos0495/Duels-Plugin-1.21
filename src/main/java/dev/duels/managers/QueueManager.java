package dev.duels.managers;

import dev.duels.DuelsPlugin;
import dev.duels.objects.DuelRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

public class QueueManager {

    private final DuelsPlugin plugin;
    private final Map<String, Deque<UUID>> kitQueues = new HashMap<>();
    private final Map<UUID, String> lastQueueKit = new HashMap<>();

    public QueueManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    public void joinQueue(Player player, String kitName) {
        if (!plugin.getKitManager().kitExists(kitName)) {
            player.sendMessage(plugin.getPrefix() + "§cKit not found: " + kitName);
            return;
        }

        UUID uuid = player.getUniqueId();

        // Welt-Whitelist: Spieler müssen in einer der unter
        // queue.allowed-worlds konfigurierten Welten stehen, um eine Queue
        // betreten zu können. Standard: nur die Lobby-Welt (die Welt, in
        // der /setspawn gesetzt wurde) ist erlaubt.
        if (!isAllowedWorld(player)) {
            player.sendMessage(plugin.getPrefix() + "§cYou can only queue from the lobby world.");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        // Spieler in einer Party können keine Queues betreten — sie sollen
        // ausschließlich über das Party-Menü duellieren (User-Wunsch).
        if (plugin.getPartyManager() != null && plugin.getPartyManager().isInParty(uuid)) {
            player.sendMessage(plugin.getPrefix() + "§cYou can't join a queue while in a party. Leave the party first.");
            return;
        }

        // Aus allen Queues entfernen
        leaveAllQueues(uuid);

        // In neue Queue einreihen
        Deque<UUID> queue = kitQueues.computeIfAbsent(kitName, k -> new ArrayDeque<>());
        if (!queue.contains(uuid)) {
            queue.addLast(uuid);
            lastQueueKit.put(uuid, kitName);
            String kitDisplay = plugin.getKitManager().getKitDisplayName(kitName);
            player.sendMessage(plugin.getPrefix() + "§aQueued §7for kit §r" + kitDisplay + "§7.");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        }

        // Queue-GUI aktualisieren
        plugin.getGuiManager().refreshQueueGUIs();
    }

    public void leaveQueue(Player player) {
        UUID uuid = player.getUniqueId();
        boolean wasInQueue = leaveAllQueues(uuid);

        if (wasInQueue) {
            player.sendMessage(plugin.getPrefix() + "§cLeft §7queue.");
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 0.8f);
        }

        plugin.getGuiManager().refreshQueueGUIs();
    }

    /**
     * Prüft, ob der Spieler in einer Welt steht, die laut Config zum Queue-
     * Beitritt berechtigt ist.
     *
     * <p>Konfigurationsschlüssel:</p>
     * <pre>
     * queue:
     *   allowed-worlds:
     *     - lobby
     *     - hub
     * </pre>
     *
     * <p>Wenn die Liste leer/nicht gesetzt ist, fällt der Check auf die
     * vom Plugin bekannte Lobby-Welt zurück (Welt mit /setspawn). Damit
     * funktioniert das Plugin ohne Config-Eintrag genauso wie vorher.</p>
     */
    public boolean isAllowedWorld(Player player) {
        if (player == null || player.getWorld() == null) return false;
        java.util.List<String> allowed = plugin.getConfig().getStringList("queue.allowed-worlds");
        if (allowed == null || allowed.isEmpty()) {
            // Fallback: Lobby-Welt (Welt mit /setspawn).
            return plugin.getPlayerManager() != null
                    && plugin.getPlayerManager().isInLobbyWorld(player);
        }
        String world = player.getWorld().getName();
        for (String w : allowed) {
            if (w != null && w.equalsIgnoreCase(world)) return true;
        }
        return false;
    }

    public boolean leaveAllQueues(UUID uuid) {
        boolean wasInQueue = false;
        for (Deque<UUID> queue : kitQueues.values()) {
            if (queue.remove(uuid)) {
                wasInQueue = true;
            }
        }
        return wasInQueue;
    }

    public void checkQueueMatches() {
        for (Map.Entry<String, Deque<UUID>> entry : kitQueues.entrySet()) {
            String kitName = entry.getKey();
            Deque<UUID> queue = entry.getValue();

            // Offline Spieler entfernen
            queue.removeIf(id -> Bukkit.getPlayer(id) == null);

            if (queue.size() >= 2) {
                // Match starten
                UUID p1 = queue.pollFirst();
                UUID p2 = queue.pollFirst();

                Player player1 = Bukkit.getPlayer(p1);
                Player player2 = Bukkit.getPlayer(p2);

                if (player1 != null && player2 != null) {
                    startQueueMatch(player1, player2, kitName);
                } else {
                    // Zurück in Queue
                    if (player1 != null) joinQueue(player1, kitName);
                    if (player2 != null) joinQueue(player2, kitName);
                }
            }
        }
    }

    private void startQueueMatch(Player player1, Player player2, String kitName) {
        // Arena holen (nach erlaubtem Kit gefiltert)
        dev.duels.objects.Arena arena = plugin.getArenaManager().getRandomAvailableArenaForKit(kitName);
        if (arena == null) {
            player1.sendMessage(plugin.getPrefix() + "§cNo arena available for this kit!");
            player2.sendMessage(plugin.getPrefix() + "§cNo arena available for this kit!");
            joinQueue(player1, kitName);
            joinQueue(player2, kitName);
            return;
        }

        // DuelRequest erstellen. Fallback konsistent mit ConfigManager-Default
        // (1); früher war hier 3, sodass Queue-Matches immer Bo3 liefen, auch
        // wenn der User default-bestof in der Config auf 1 gesetzt hatte und
        // noch kein Key existierte.
        int bestOf = plugin.getConfigManager().getMainConfig().getInt("default-bestof", 1);
        DuelRequest request = new DuelRequest(
                player1.getUniqueId(),
                player2.getUniqueId(),
                kitName,
                arena.getName(),
                bestOf
        );

        // Duel starten
        plugin.getDuelManager().startDuel(request);
    }

    public boolean isInQueue(UUID uuid) {
        for (Deque<UUID> queue : kitQueues.values()) {
            if (queue.contains(uuid)) {
                return true;
            }
        }
        return false;
    }

    public boolean isInQueue(UUID uuid, String kitName) {
        Deque<UUID> queue = kitQueues.get(kitName);
        return queue != null && queue.contains(uuid);
    }

    public int getQueueSize(String kitName) {
        Deque<UUID> queue = kitQueues.get(kitName);
        return queue != null ? queue.size() : 0;
    }

    public int getPlayingCount(String kitName) {
        int count = 0;

        // Verwende getAllSessions() statt getActiveDuelCount()
        for (dev.duels.objects.DuelSession session : plugin.getDuelManager().getAllSessions()) {
            if (session.getKitName().equals(kitName)) {
                count += 2; // beide Spieler
            }
        }
        return count;
    }

    public String getLastQueueKit(UUID uuid) {
        return lastQueueKit.get(uuid);
    }

    public void setLastQueueKit(UUID uuid, String kitName) {
        lastQueueKit.put(uuid, kitName);
    }

    public void cleanup() {
        kitQueues.clear();
        lastQueueKit.clear();
    }
}