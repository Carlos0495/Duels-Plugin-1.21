package dev.duels.managers;

import dev.duels.DuelsPlugin;
import dev.duels.objects.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class PlayerManager {

    private final DuelsPlugin plugin;
    private final Map<UUID, PlayerData> playerData = new HashMap<>();
    private final Set<UUID> hiddenPlayers = new HashSet<>();
    private final Map<UUID, Boolean> autoFly = new HashMap<>();
    // Spieler, deren Teleport vom Plugin selbst ausgelöst wurde (z.B. Match-
    // Start, Lobby-Rückkehr). Solche TPs dürfen den Party-Welt-Lock umgehen.
    private final Set<UUID> teleportBypass = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** True, wenn der aktuelle Teleport vom Plugin selbst ausgelöst wird. */
    public boolean isTeleportBypass(UUID uuid) {
        return uuid != null && teleportBypass.contains(uuid);
    }

    public PlayerManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }


    public void loadPlayerData(UUID uuid) {
        if (playerData.containsKey(uuid) && autoFly.containsKey(uuid)) return;

        String key = uuid.toString();

        PlayerData data = playerData.computeIfAbsent(uuid, k -> new PlayerData(uuid));

        data.setKills(plugin.getConfigManager().getPlayersConfig().getInt(key + ".kills", 0));
        data.setDeaths(plugin.getConfigManager().getPlayersConfig().getInt(key + ".deaths", 0));
        data.setWins(plugin.getConfigManager().getPlayersConfig().getInt(key + ".wins", 0));
        data.setLosses(plugin.getConfigManager().getPlayersConfig().getInt(key + ".losses", 0));
        data.setCoins(plugin.getConfigManager().getPlayersConfig().getInt(key + ".coins", 0));
        data.setName(plugin.getConfigManager().getPlayersConfig().getString(key + ".name", "Unknown"));

        boolean af = plugin.getConfigManager().getPlayersConfig().getBoolean(key + ".autofly", true);
        autoFly.put(uuid, af);
    }

    public void loadPlayerData() {
        playerData.clear();
        autoFly.clear();
        hiddenPlayers.clear();

        for (String key : plugin.getConfigManager().getPlayersConfig().getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                loadPlayerData(uuid); // nutzt deine Single-Loader Methode
            } catch (IllegalArgumentException ignored) {}
        }
    }


    public void savePlayerData(UUID uuid) {
        PlayerData data = playerData.get(uuid);
        if (data == null) return;

        String key = uuid.toString();
        plugin.getConfigManager().getPlayersConfig().set(key + ".kills", data.getKills());
        plugin.getConfigManager().getPlayersConfig().set(key + ".deaths", data.getDeaths());
        plugin.getConfigManager().getPlayersConfig().set(key + ".wins", data.getWins());
        plugin.getConfigManager().getPlayersConfig().set(key + ".losses", data.getLosses());
        plugin.getConfigManager().getPlayersConfig().set(key + ".coins", data.getCoins());
        plugin.getConfigManager().getPlayersConfig().set(key + ".name", data.getName());
        plugin.getConfigManager().getPlayersConfig().set(key + ".autofly", autoFly.getOrDefault(uuid, true));

        plugin.getConfigManager().savePlayersConfig();
    }

    public void saveAllData() {
        for (UUID uuid : playerData.keySet()) {
            savePlayerData(uuid);
        }
    }

    public PlayerData getPlayerData(UUID uuid) {
        loadPlayerData(uuid);
        return playerData.computeIfAbsent(uuid, k -> new PlayerData(uuid));
    }



    public void updateAllScoreboards() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getScoreboardManager().updateScoreboard(player);
        }
    }

    public void addStat(UUID uuid, String stat, int amount) {
        PlayerData data = getPlayerData(uuid);
        switch (stat.toLowerCase()) {
            case "kills":
                data.setKills(data.getKills() + amount);
                break;
            case "deaths":
                data.setDeaths(data.getDeaths() + amount);
                break;
            case "wins":
                data.setWins(data.getWins() + amount);
                break;
            case "losses":
                data.setLosses(data.getLosses() + amount);
                break;
            case "coins":
                data.setCoins(data.getCoins() + amount);
                break;
        }
        savePlayerData(uuid);
    }

    public void setStat(UUID uuid, String stat, int value) {
        PlayerData data = getPlayerData(uuid);
        switch (stat.toLowerCase()) {
            case "kills":
                data.setKills(value);
                break;
            case "deaths":
                data.setDeaths(value);
                break;
            case "wins":
                data.setWins(value);
                break;
            case "losses":
                data.setLosses(value);
                break;
            case "coins":
                data.setCoins(value);
                break;
        }
        savePlayerData(uuid);
    }
public List<PlayerData> getAllPlayerDataSnapshot() {
    List<PlayerData> list = new ArrayList<>();

    for (String key : plugin.getConfigManager().getPlayersConfig().getKeys(false)) {
        if (!isValidUUID(key)) continue;

        UUID uuid;
        try {
            uuid = UUID.fromString(key);
        } catch (IllegalArgumentException ignored) {
            continue;
        }

        loadPlayerData(uuid);
        PlayerData data = getPlayerData(uuid);
        if (data != null) list.add(data);
    }
    return list;
}

/**
 * Liefert die nach {@code category} absteigend sortierte Rangliste aller
 * bekannten Spieler. Unterstützte Kategorien: kills, deaths, wins, losses,
 * coins, kd, winrate. Bei Gleichstand wird alphabetisch nach Name sortiert.
 */
public List<PlayerData> getLeaderboard(String category) {
    List<PlayerData> all = getAllPlayerDataSnapshot();
    final String cat = category == null ? "kills" : category.toLowerCase();
    all.sort((a, b) -> {
        double av = leaderboardValue(a, cat);
        double bv = leaderboardValue(b, cat);
        if (Double.compare(bv, av) != 0) return Double.compare(bv, av);
        String an = a.getName() == null ? "" : a.getName();
        String bn = b.getName() == null ? "" : b.getName();
        return an.compareToIgnoreCase(bn);
    });
    return all;
}

private double leaderboardValue(PlayerData pd, String cat) {
    switch (cat) {
        case "deaths":  return pd.getDeaths();
        case "wins":    return pd.getWins();
        case "losses":  return pd.getLosses();
        case "coins":   return pd.getCoins();
        case "kd":      return pd.getDeaths() == 0 ? pd.getKills() : (double) pd.getKills() / pd.getDeaths();
        case "winrate": {
            int total = pd.getWins() + pd.getLosses();
            return total == 0 ? 0.0 : ((double) pd.getWins() / total) * 100.0;
        }
        case "kills":
        default:        return pd.getKills();
    }
}

/** Formatiert den Ranglisten-Wert eines Spielers für die Anzeige. */
public String formatLeaderboardValue(PlayerData pd, String category) {
    String cat = category == null ? "kills" : category.toLowerCase();
    switch (cat) {
        case "kd":      return String.format(java.util.Locale.US, "%.2f",
                pd.getDeaths() == 0 ? (double) pd.getKills() : (double) pd.getKills() / pd.getDeaths());
        case "winrate": {
            int total = pd.getWins() + pd.getLosses();
            double wr = total == 0 ? 0.0 : ((double) pd.getWins() / total) * 100.0;
            return String.format(java.util.Locale.US, "%.1f%%", wr);
        }
        default:        return String.valueOf((long) leaderboardValue(pd, cat));
    }
}

    public void setupPlayerInventory(Player player) {
        // In der Lobby-Welt: Hotbar setzen (HotbarManager.applyMode räumt
        // davor Slots 0-8 ab und schreibt die konfigurierten Items rein).
        // Außerhalb der Lobby-Welt: NUR die PDC-getaggten Hotbar-Items aus
        // dem Inventar entfernen, sonst NICHTS anfassen — Rüstung, Offhand
        // und sonstige Items des Spielers bleiben unangetastet (User-Bug:
        // Welt-Wechsel hat vorher Rüstung gelöscht).
        if (!isInLobbyWorld(player)) {
            removeHotbarItems(player);
            player.updateInventory();
            return;
        }

        // In der Lobby-Welt: Hotbar via HotbarManager applizieren. Das setzt
        // automatisch Slot 0-8. Armor + Offhand sind in der Lobby grund-
        // sätzlich nicht vorgesehen — die werden nur hier (Lobby) geclearet.
        var inv = player.getInventory();
        // Komplettes Lobby-Clear: Inventar + Rüstung + Offhand. Das wird
        // sowohl von /spawn als auch nach Duel/FFA-Ende ausgeführt — User
        // soll mit nackter Lobby-Hotbar starten, keine Duel-Items übrig.
        inv.clear();
        inv.setHelmet(null);
        inv.setChestplate(null);
        inv.setLeggings(null);
        inv.setBoots(null);
        inv.setItemInOffHand(null);
        player.setItemOnCursor(null);

        HotbarManager hotbar = plugin.getHotbarManager();
        if (hotbar == null) {
            player.getInventory().clear();
            player.updateInventory();
            return;
        }

        String mode = HotbarManager.MODE_LOBBY;
        PartyManager pm = plugin.getPartyManager();
        if (pm != null && pm.isInParty(player.getUniqueId())) {
            mode = pm.isLeader(player.getUniqueId())
                    ? HotbarManager.MODE_PARTY_LEADER
                    : HotbarManager.MODE_PARTY_MEMBER;
        }
        hotbar.applyMode(player, mode);
    }

    /**
     * Räumt alle Nicht-Hotbar-Items aus dem Inventar eines Spielers in der
     * Lobby-Welt (Survival). Hotbar-Items (PDC-getaggt) in Slot 0–8 bleiben
     * stehen, alle anderen Slots (inkl. Storage 9–35, Rüstung, Offhand,
     * Cursor) werden geleert. Wird als Tick-Loop aufgerufen, damit Items
     * die per Drag-and-Drop aus einer GUI ins Spieler-Inventar gelangen
     * sind, sofort wieder verschwinden.
     */
    public void clearNonHotbarItems(Player player) {
        if (player == null || !player.isOnline()) return;
        if (player.getGameMode() != GameMode.SURVIVAL) return;
        if (!isInLobbyWorld(player)) return;

        HotbarManager hm = plugin.getHotbarManager();
        if (hm == null) return;

        var inv = player.getInventory();
        // Slots 0-8 sind die Hotbar — nur Items OHNE Hotbar-Tag clearen.
        for (int i = 0; i <= 8; i++) {
            var item = inv.getItem(i);
            if (item == null) continue;
            String action = hm.readAction(item);
            if (action == null || action.isEmpty()) {
                inv.setItem(i, null);
            }
        }
        // Storage 9-35 komplett clearen.
        for (int i = 9; i <= 35; i++) {
            inv.setItem(i, null);
        }
        // Rüstung + Offhand + Cursor clearen.
        inv.setHelmet(null);
        inv.setChestplate(null);
        inv.setLeggings(null);
        inv.setBoots(null);
        var off = inv.getItemInOffHand();
        if (off != null) {
            String action = hm.readAction(off);
            if (action == null || action.isEmpty()) {
                inv.setItemInOffHand(null);
            }
        }
        var cursor = player.getItemOnCursor();
        if (cursor != null && cursor.getType() != Material.AIR) {
            // Nicht räumen wenn der Spieler gerade ein GUI offen hat (sonst
            // bricht jeder Klick im GUI ab). Nur wenn das Top-Inventar das
            // Player-Inv selbst ist (= kein GUI offen).
            if (player.getOpenInventory() != null
                    && player.getOpenInventory().getTopInventory() != null
                    && player.getOpenInventory().getTopInventory().equals(inv) == false
                    && player.getOpenInventory().getType()
                            == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                player.setItemOnCursor(null);
            }
        }
    }

    /**
     * Setzt den Tab-Liste-Anzeigenamen eines Spielers mit Status-Suffix:
     * "⚔" wenn im Duel/FFA, "👁" wenn spectating, sonst leer.
     */
    public void updateTabName(Player player) {
        // No-op. Vorher: setPlayerListName(player + " ⚔") — das hat den
        // %luckperms_prefix% des TAB-Plugins gekillt. Da das ⚔/👁 jetzt
        // via PAPI-Placeholder %duels_status% im TAB-Format dargestellt
        // wird, ist setPlayerListName komplett überflüssig.
        if (player == null) return;
    }

    /**
     * Entfernt alle PDC-getaggten Hotbar-Items aus dem Inventar. Andere
     * Items, Rüstung und Offhand bleiben unangetastet. Wird beim Verlassen
     * der Lobby-Welt aufgerufen.
     */
    public void removeHotbarItems(Player player) {
        if (player == null) return;
        HotbarManager hm = plugin.getHotbarManager();
        if (hm == null) return;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            var item = inv.getItem(i);
            if (item == null) continue;
            String action = hm.readAction(item);
            if (action != null && !action.isEmpty()) {
                inv.setItem(i, null);
            }
        }
        // Offhand auch checken (falls dort ein Hotbar-Item gelandet ist).
        var off = inv.getItemInOffHand();
        if (off != null) {
            String action = hm.readAction(off);
            if (action != null && !action.isEmpty()) {
                inv.setItemInOffHand(null);
            }
        }
    }

    /** True, falls der Spieler in der Welt steht, in der der Plugin-Spawn gesetzt ist. */
    public boolean isInLobbyWorld(Player player) {
        if (player == null || player.getWorld() == null) return false;
        Location spawn = plugin.getArenaManager().getSpawnLocation();
        if (spawn == null || spawn.getWorld() == null) {
            // Spawn nicht (oder noch nicht) gesetzt: vorsichtshalber als
            // Lobby behandeln (sonst hätte der User keine Hotbar bevor er
            // /setspawn macht).
            return true;
        }
        return player.getWorld().getUID().equals(spawn.getWorld().getUID());
    }
    public void applyVisibility(Player viewer) {
        if (viewer == null) return;

        boolean hidden = isHidden(viewer.getUniqueId());

        // Tablist-/Sicht-Filter bestimmen (Match-Filter hat Vorrang vor
        // Welt-Filter). Match-Peers (Gegner/Mitspieler) sind IMMER sichtbar,
        // damit Duelisten sich gegenseitig sehen — unabhängig von Toggles.
        java.util.Set<UUID> peers = getMatchPeers(viewer.getUniqueId());
        var cfg = plugin.getConfigManager();
        boolean duelFilter = cfg.isDuelTablistFilter() && peers != null;
        boolean sameWorldOnly = !duelFilter && viewer.getWorld() != null
                && cfg.getPerWorldTablistWorlds().contains(viewer.getWorld().getName().toLowerCase());
        // In diesen Welten ist das Spieler-Verstecken automatisch AUS: der
        // Viewer sieht IMMER alle anderen Spieler (Hide-Toggle wird ignoriert).
        boolean forceShowAll = peers == null && viewer.getWorld() != null
                && cfg.isAlwaysShowPlayersWorld(viewer.getWorld().getName());

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(viewer)) continue;

            boolean show;
            if (peers != null && peers.contains(other.getUniqueId())) {
                show = true;
            } else if (forceShowAll) {
                show = true;
            } else if (hidden) {
                show = false;
            } else if (duelFilter) {
                show = false;
            } else if (sameWorldOnly) {
                show = other.getWorld() != null && other.getWorld().equals(viewer.getWorld());
            } else {
                show = true;
            }

            if (show) {
                viewer.showPlayer(plugin, other);
            } else {
                viewer.hidePlayer(plugin, other);
            }
        }
    }

    /** Wendet {@link #applyVisibility(Player)} für alle Online-Spieler an. */
    public void refreshAllVisibility() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            applyVisibility(viewer);
        }
    }

    /**
     * Liefert die "Match-Peers" eines Spielers (alle Teilnehmer seines
     * aktuellen Duel-/FFA-/Team-Matches inkl. ihm selbst) oder {@code null},
     * wenn er in keinem Match ist. Wird für Chat- und Tablist-Filter genutzt.
     */
    public java.util.Set<UUID> getMatchPeers(UUID uuid) {
        if (uuid == null) return null;
        if (plugin.getDuelManager().isInDuel(uuid)) {
            dev.duels.objects.DuelSession s = plugin.getDuelManager().getDuelSession(uuid);
            if (s != null) {
                java.util.Set<UUID> set = new java.util.HashSet<>();
                set.add(s.getPlayer1());
                set.add(s.getPlayer2());
                return set;
            }
        }
        if (plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(uuid)) {
            dev.duels.managers.PartyFFAManager.FFASession s =
                    plugin.getPartyFFAManager().getSession(uuid);
            if (s != null) return new java.util.HashSet<>(s.allParticipants);
        }
        return null;
    }


    public void handleJoinVisibility(Player joiner) {
        if (joiner == null) return;

        // Apply joiner's own preference
        applyVisibility(joiner);

        // If other players have visibility OFF, they should hide the joiner too —
        // außer der Viewer ist in einer Welt, in der Verstecken automatisch AUS ist.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(joiner)) continue;
            boolean viewerForceShow = viewer.getWorld() != null
                    && plugin.getConfigManager().isAlwaysShowPlayersWorld(viewer.getWorld().getName())
                    && getMatchPeers(viewer.getUniqueId()) == null;
            if (!viewerForceShow && isHidden(viewer.getUniqueId())) {
                viewer.hidePlayer(plugin, joiner);
            }
        }
    }

    public void refreshQueueSlotItem(Player player) {
        UUID uuid = player.getUniqueId();

        if (plugin.getDuelManager().isInDuel(uuid)) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        // Nur im Lobby-Hotbar-Mode relevant. Wenn der Spieler in einer Party
        // ist, belegt der Party-Hotbar bereits Slot 4 — dann nicht
        // überschreiben.
        if (plugin.getPartyManager() != null && plugin.getPartyManager().isInParty(uuid)) return;

        // Slot 4 (Queue) aus config lesen — falls der User den Slot umkonfiguriert
        // hat, ehren wir das.
        int queueSlot = 4;
        String configuredSlotPath = "hotbar.lobby.queue.slot";
        if (plugin.getConfigManager().getMainConfig().contains(configuredSlotPath)) {
            queueSlot = plugin.getConfigManager().getMainConfig().getInt(configuredSlotPath, 4);
        }

        org.bukkit.NamespacedKey actionKey = plugin.getHotbarManager() != null
                ? plugin.getHotbarManager().getActionKey()
                : null;

        if (plugin.getQueueManager().isInQueue(uuid)) {
            ItemStack leaveQueue = new ItemStack(Material.BARRIER);
            ItemMeta meta = leaveQueue.getItemMeta();
            meta.setDisplayName("§cʟᴇᴀᴠᴇ ǫᴜᴇᴜᴇ §7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
            meta.setLore(java.util.Arrays.asList("§7Click to leave your current queue."));
            if (actionKey != null) {
                meta.getPersistentDataContainer().set(actionKey,
                        org.bukkit.persistence.PersistentDataType.STRING,
                        HotbarManager.ACTION_QUEUE_DYNAMIC);
            }
            leaveQueue.setItemMeta(meta);
            player.getInventory().setItem(queueSlot, leaveQueue);
        } else {
            ItemStack joinQueue = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta headMeta = (SkullMeta) joinQueue.getItemMeta();
            headMeta.setDisplayName("§aᴊᴏɪɴ ʟᴀѕᴛ ǫᴜᴇᴜᴇ ᴀɢᴀɪɴ §7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
            headMeta.setLore(java.util.Arrays.asList("§7Here you can join the same Queue again."));
            headMeta.setOwningPlayer(player);
            if (actionKey != null) {
                headMeta.getPersistentDataContainer().set(actionKey,
                        org.bukkit.persistence.PersistentDataType.STRING,
                        HotbarManager.ACTION_QUEUE_DYNAMIC);
            }
            joinQueue.setItemMeta(headMeta);
            player.getInventory().setItem(queueSlot, joinQueue);
        }

        player.updateInventory();
    }

    public void teleportToSpawn(Player player) {
        Location spawn = plugin.getArenaManager().getSpawnLocation();
        if (spawn == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("general.spawn-not-set", "&cSpawn has not been set yet!"));
            return;
        }
        attemptSpawnTeleport(player, spawn, 0);
    }

    /**
     * Robustly teleports a player to spawn. A bare {@code player.teleport()} can
     * silently fail (return false) when the player still rides/has a vehicle, is
     * spectating an entity (camera attached), or has passengers — in that case
     * the old code still handed out the lobby hotbar items but never moved the
     * player. We clear those blockers first and retry a few times before
     * applying the lobby state.
     */
    private void attemptSpawnTeleport(Player player, Location spawn, int attempt) {
        if (player == null || !player.isOnline()) return;

        // Clear common teleport blockers.
        if (player.getSpectatorTarget() != null) player.setSpectatorTarget(null);
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
        if (player.isInsideVehicle()) player.leaveVehicle();
        if (!player.getPassengers().isEmpty()) player.eject();

        boolean ok;
        try {
            ok = player.teleport(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } catch (Throwable t) {
            ok = false;
        }

        if (!ok && attempt < 3) {
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin,
                    () -> attemptSpawnTeleport(player, spawn, attempt + 1), 2L);
            return;
        }

        if (!plugin.getDuelManager().isInDuel(player.getUniqueId())) {
            forceLobbyState(player);
            setupPlayerInventory(player);
            refreshQueueSlotItem(player);
            // Fly mit kurzem Delay applizieren — nach Cross-World-Teleport
            // oder redundantem setGameMode kann Paper den Flight-State
            // zurücksetzen. 2-Tick-Delay (wie bei onJoin) garantiert, dass
            // der Teleport vollständig abgeschlossen ist.
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) applyLobbyFly(player);
            }, 2L);
        }

        plugin.getScoreboardManager().updateScoreboard(player);
    }

    /**
     * Robuster Teleport für beliebige Ziele (Arena-Spawn, Lobby, Runden-
     * Respawn usw.). Ein nacktes {@code player.teleport()} schlägt manchmal
     * still fehl (gibt false zurück), wenn der Spieler noch in einem Vehikel
     * sitzt, Passagiere hat, gerade eine Entity spectatet (Kamera) oder der
     * Ziel-Chunk noch nicht geladen ist. Das führte zum Bug "manchmal
     * funktioniert jeglicher TP einfach nicht" (Spieler bekam Kit/Items, wurde
     * aber nicht bewegt). Wir lösen die Blocker, laden den Ziel-Chunk und
     * wiederholen den Teleport ein paar Mal.
     *
     * @return true, wenn der Teleport (sofort) erfolgreich war.
     */
    public boolean safeTeleport(Player player, Location dest) {
        if (player == null || dest == null || dest.getWorld() == null) return false;
        // Plugin-eigener Teleport: Party-Welt-Lock umgehen, auch über die
        // (verzögerten) Retries hinweg. Bypass wird kurz danach wieder entfernt.
        final UUID id = player.getUniqueId();
        teleportBypass.add(id);
        boolean result = attemptSafeTeleport(player, dest, 0);
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin,
                () -> teleportBypass.remove(id), 10L);
        return result;
    }

    private boolean attemptSafeTeleport(Player player, Location dest, int attempt) {
        if (player == null || !player.isOnline() || dest == null || dest.getWorld() == null) return false;

        // Häufige Teleport-Blocker auflösen.
        try { if (player.getSpectatorTarget() != null) player.setSpectatorTarget(null); } catch (Throwable ignored) {}
        if (player.isInsideVehicle()) player.leaveVehicle();
        if (!player.getPassengers().isEmpty()) player.eject();
        // Ziel-Chunk laden — Teleport in einen ungeladenen Chunk schlägt
        // gelegentlich still fehl.
        try { dest.getWorld().getChunkAt(dest).load(); } catch (Throwable ignored) {}

        boolean ok;
        try {
            ok = player.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } catch (Throwable t) {
            ok = false;
        }

        if (!ok && attempt < 3) {
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin,
                    () -> attemptSafeTeleport(player, dest, attempt + 1), 2L);
        }
        return ok;
    }

    // Füge diese Methoden zur PlayerManager Klasse hinzu:

    public void forceLobbyState(Player player) {
        if (player == null) return;

        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setRemainingAir(player.getMaximumAir());
        player.setFreezeTicks(0);
        player.setAbsorptionAmount(0.0);

        // Potion Effects entfernen
        player.getActivePotionEffects().forEach(effect ->
                player.removePotionEffect(effect.getType()));

        // GameMode nur setzen wenn nötig — Paper kann bei redundantem
        // setGameMode(SURVIVAL) die Flight-Flags zurücksetzen, was dazu
        // führt dass /spawn das Fliegen deaktiviert obwohl es an war.
        if (player.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
    }


    public void applyLobbyFly(Player player) {
        if (player == null) return;

        if (player.getGameMode() == GameMode.CREATIVE) return;

        if (!isInLobby(player)) {
            player.setFlying(false);
            player.setAllowFlight(false);
            return;
        }

        if (plugin.getConfigManager().isAutoDisableFly() && !player.hasPermission("duels.fly")) {
            player.setFlying(false);
            player.setAllowFlight(false);
            return;
        }

        if (!getAutoFly(player.getUniqueId())) {
            player.setFlying(false);
            player.setAllowFlight(false);
            return;
        }

        player.setAllowFlight(true);
        player.setFlying(true);
    }

    public boolean isInLobby(Player player) {
        if (player == null) return false;
        if (plugin.getDuelManager().isInDuel(player.getUniqueId())) return false;

        Location spawn = plugin.getArenaManager().getSpawnLocation();
        if (spawn != null && player.getWorld() != null) {
            return player.getWorld().getUID().equals(spawn.getWorld().getUID());
        }
        return true;
    }

    // --- DUEL VISIBILITY ---


    public void applyDuelVisibility(Player p1, Player p2) {
        if (p1 == null || p2 == null) return;

        // User-Wunsch: Spieler im Duel bleiben in der Tab-Liste sichtbar.
        // Statt sie hart zu verstecken, sorgen wir nur dafür, dass die
        // beiden Duelisten sich gegenseitig sehen können (falls sie vorher
        // per Visibility-Toggle versteckt waren).
        p1.showPlayer(plugin, p2);
        p2.showPlayer(plugin, p1);
    }


    public void restoreAllVisibility() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            // First: show everyone to reset hard-hide from duel
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(other)) continue;
                viewer.showPlayer(plugin, other);
            }

            // Then apply viewer's preference (hidden or not)
            applyVisibility(viewer);
            updatePlayerVisibility(viewer);
        }
    }


    public void enforceDuelPrivacyForJoin(Player joiner) {
        // User-Wunsch: Spieler in Duels bleiben in der Tab-Liste sichtbar.
        // Wir verstecken sie nicht mehr beim Join eines neuen Spielers.
        // (Methode bleibt als No-Op für API-Kompatibilität.)
    }



    public void updatePlayerVisibility(Player player) {
        if (player == null) return;
        // Visibility-Item NUR in der Lobby-Welt setzen. Sonst würde z.B. nach
        // einem Duel-Ende (restoreAllVisibility loopt über ALLE Spieler) das
        // Item in Slot 7 jedes Spielers in einer anderen Welt landen und das
        // aktuelle Item überschreiben (User-Bug: "Visibility item ersetzt
        // bei jedem anderen das aktuelle Item wenn jemand in die Lobby geht").
        if (!isInLobbyWorld(player)) return;
        // Spieler im Duel/FFA: Visibility-Item NICHT ins Inventar setzen,
        // da es das aktive Kit ersetzen würde (User-Bug: "Visibility item
        // ersetzt aktuelles Item wenn jemand /spawn macht").
        if (plugin.getDuelManager().isInDuel(player.getUniqueId())) return;
        if (plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(player.getUniqueId())) return;
        // Spectator: ebenfalls kein Visibility-Item (leeres Inv im Spectator-Mode).
        if (plugin.getSpectateManager() != null
                && plugin.getSpectateManager().isSpectating(player.getUniqueId())) return;

        boolean hidden = isHidden(player.getUniqueId());
        ItemStack visibilityItem = new ItemStack(hidden ? Material.RED_DYE : Material.GREEN_DYE);
        ItemMeta meta = visibilityItem.getItemMeta();

        if (hidden) {
            meta.setDisplayName("§cᴘʟᴀʏᴇʀ ᴠɪѕɪʙɪʟɪᴛʏ ᴏꜰꜰ §7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
            meta.setLore(java.util.Arrays.asList("§7Change the Player visibility", "§c✗ Players are invisible"));
        } else {
            meta.setDisplayName("§aᴘʟᴀʏᴇʀ ᴠɪѕɪʙɪʟɪᴛʏ ᴏɴ §7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
            meta.setLore(java.util.Arrays.asList("§7Change the Player visibility", "§a✓ Players are visible"));
        }

        // PDC-Tag setzen, damit das Item vom Hotbar-Clear-Loop NICHT als
        // "non-hotbar" erkannt und gelöscht wird (Bug-Report: nach Klick
        // verschwand das Visibility-Item, weil der neu gebaute ItemStack
        // den Action-Tag nicht hatte und der 4-Tick-Clear-Loop es danach
        // entfernt hat).
        if (plugin.getHotbarManager() != null && meta != null) {
            meta.getPersistentDataContainer().set(
                    plugin.getHotbarManager().getActionKey(),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    HotbarManager.ACTION_VISIBILITY);
        }

        visibilityItem.setItemMeta(meta);

        // Slot aus Config lesen (falls User den Visibility-Slot umkonfiguriert hat).
        int visibilitySlot = 7;
        String configuredSlotPath = "hotbar.lobby.visibility.slot";
        if (plugin.getConfigManager() != null
                && plugin.getConfigManager().getMainConfig().contains(configuredSlotPath)) {
            visibilitySlot = plugin.getConfigManager().getMainConfig().getInt(configuredSlotPath, 7);
        }
        player.getInventory().setItem(visibilitySlot, visibilityItem);
        player.updateInventory();
    }

    public UUID getUUIDFromName(String playerName) {
        // Online Spieler zuerst checken
        Player onlinePlayer = org.bukkit.Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
            return onlinePlayer.getUniqueId();
        }

        // Aus Config suchen
        for (String key : plugin.getConfigManager().getPlayersConfig().getKeys(false)) {
            if (isValidUUID(key)) {
                String storedName = plugin.getConfigManager().getPlayersConfig().getString(key + ".name");
                if (storedName != null && storedName.equalsIgnoreCase(playerName)) {
                    try {
                        return UUID.fromString(key);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                }
            }
        }

        return null;
    }
    public int getStat(UUID uuid, String stat) {
        PlayerData data = getPlayerData(uuid);
        switch (stat.toLowerCase()) {
            case "kills": return data.getKills();
            case "deaths": return data.getDeaths();
            case "wins": return data.getWins();
            case "losses": return data.getLosses();
            case "coins": return data.getCoins();
            default: return 0;
        }
    }

    public String calculateWinrate(int wins, int losses) {
        int total = wins + losses;
        if (total == 0) return "0%";
        double winrate = ((double) wins / total) * 100;
        return String.format("%.1f%%", winrate);
    }

    public boolean isHidden(UUID uuid) {
        return hiddenPlayers.contains(uuid);
    }

    public void toggleVisibility(UUID uuid) {
        if (hiddenPlayers.contains(uuid)) {
            hiddenPlayers.remove(uuid);
        } else {
            hiddenPlayers.add(uuid);
        }
    }

    public boolean getAutoFly(UUID uuid) {
        return autoFly.getOrDefault(uuid, true);
    }

    public void setAutoFly(UUID uuid, boolean value) {
        autoFly.put(uuid, value);
        savePlayerData(uuid);
    }

    /**
     * Setzt den Autofly-Status und persistiert ihn direkt in players.yml —
     * funktioniert auch für OFFLINE-Spieler (deren PlayerData nicht geladen ist).
     */
    public void setAutoFlyPersistent(UUID uuid, boolean value) {
        autoFly.put(uuid, value);
        String key = uuid.toString();
        plugin.getConfigManager().getPlayersConfig().set(key + ".autofly", value);
        plugin.getConfigManager().savePlayersConfig();
    }

    /**
     * Setzt permission-basierte Defaults durch: ohne {@code duels.fly} wird
     * Fly aus, ohne Armortrim-Permission werden die Armortrims entfernt —
     * jeweils nur wenn der entsprechende Config-Toggle aktiv ist.
     */
    public void enforcePermissionDefaults(Player player) {
        if (player == null) return;
        var cm = plugin.getConfigManager();
        if (cm.isAutoDisableFly() && !player.hasPermission("duels.fly")) {
            player.setFlying(false);
            player.setAllowFlight(false);
            autoFly.put(player.getUniqueId(), false);
        }
        if (cm.isAutoDisableArmortrim()
                && plugin.getArmorTrimManager() != null
                && !player.hasPermission(dev.duels.managers.ArmorTrimManager.PERMISSION)) {
            for (dev.duels.managers.ArmorTrimManager.Piece piece
                    : dev.duels.managers.ArmorTrimManager.Piece.values()) {
                plugin.getArmorTrimManager().clearPiece(player.getUniqueId(), piece);
            }
        }
    }

    private boolean isValidUUID(String string) {
        try {
            UUID.fromString(string);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}