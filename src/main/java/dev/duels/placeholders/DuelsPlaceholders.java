package dev.duels.placeholders;

import dev.duels.DuelsPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI-Expansion für externe Plugins (z.B. TAB by NEZNAMY, das den
 * Player-List-Name pluginübergreifend überschreibt).
 *
 * <p>Verfügbare Platzhalter:</p>
 * <ul>
 *   <li>{@code %duels_status%}: " §c⚔" wenn der Spieler im Duel/FFA ist,
 *       " §7👁" wenn er spectatet, sonst leer. Empfohlen für TAB-Suffix.</li>
 *   <li>{@code %duels_status_icon%}: nur das Symbol (⚔/👁) ohne Farbe.</li>
 *   <li>{@code %duels_coins%}: Coin-Stand des Spielers.</li>
 *   <li>{@code %duels_wins%}, {@code %duels_losses%}, {@code %duels_kills%},
 *       {@code %duels_deaths%}: Stats.</li>
 * </ul>
 */
public class DuelsPlaceholders extends PlaceholderExpansion {

    private final DuelsPlugin plugin;

    public DuelsPlaceholders(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "duels";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Duels";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        String lower = params.toLowerCase();
        // Kit-bezogene Counter:
        //   %duels_playing_<kit>%  -> Anzahl Spieler gerade IM Duel mit dem Kit
        //                            (inkl. Party-FFA-Teilnehmer mit dem Kit)
        //   %duels_queue_<kit>%    -> Anzahl Spieler in der Warteschlange für das Kit
        if (lower.startsWith("playing_")) {
            String kit = lower.substring("playing_".length());
            int count = 0;
            if (plugin.getDuelManager() != null) {
                for (dev.duels.objects.DuelSession s : plugin.getDuelManager().getAllSessions()) {
                    if (kit.equalsIgnoreCase(s.getKitName())) count += 2;
                }
            }
            if (plugin.getPartyFFAManager() != null) {
                for (dev.duels.managers.PartyFFAManager.FFASession s
                        : plugin.getPartyFFAManager().getAllSessions()) {
                    if (kit.equalsIgnoreCase(s.kitName)) count += s.alive.size();
                }
            }
            return String.valueOf(count);
        }
        if (lower.startsWith("queue_")) {
            String kit = lower.substring("queue_".length());
            return String.valueOf(plugin.getQueueManager() == null
                    ? 0 : plugin.getQueueManager().getQueueSize(kit));
        }

        // Armor-Trim-Placeholders: %duels_armortrim_<piece>% und
        // %duels_material_<piece>%. Optional kann ein Spielername als
        // Suffix angehängt werden: %duels_armortrim_helmet_Steve%.
        // Wenn kein Name angegeben ist, wird der aktuell aufrufende
        // Spieler benutzt. <piece> ist eins aus: helmet/chestplate/leggings/boots.
        if (lower.startsWith("armortrim_") || lower.startsWith("material_")) {
            return resolveArmorTrimPlaceholder(player, lower);
        }

        // Ranglisten-Placeholder: %duels_<kategorie>_<platz>% (Top 10).
        //   %duels_kills_1%        → komplette Zeile (#1 Name - Wert)
        //   %duels_coins_3_name%   → nur der Name auf Platz 3 (Coins)
        //   %duels_winrate_1_value%→ nur der Wert auf Platz 1
        // Kategorien: kills, deaths, wins, losses, coins, kd, winrate
        String lbResult = resolveLeaderboardPlaceholder(lower);
        if (lbResult != null) return lbResult;

        switch (lower) {
            case "status": {
                Player on = player.getPlayer();
                if (on == null) return "";
                boolean inDuel = plugin.getDuelManager() != null
                        && plugin.getDuelManager().isInDuel(on.getUniqueId());
                boolean inFFA = plugin.getPartyFFAManager() != null
                        && plugin.getPartyFFAManager().isParticipant(on.getUniqueId());
                boolean spec = plugin.getSpectateManager() != null
                        && plugin.getSpectateManager().isSpectating(on.getUniqueId());
                org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
                if (spec) return org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        cfg.getString("status.spec-symbol", "&7 👁"));
                if (inDuel || inFFA) return org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        cfg.getString("status.duel-symbol", "&c ⚔"));
                boolean inLobby = plugin.getPlayerManager() != null
                        && plugin.getPlayerManager().isInLobbyWorld(on);
                String key = inLobby ? "status.lobby-symbol" : "status.world-symbol";
                return org.bukkit.ChatColor.translateAlternateColorCodes('&', cfg.getString(key, ""));
            }
            case "status_icon": {
                Player on = player.getPlayer();
                if (on == null) return "";
                boolean inDuel = plugin.getDuelManager().isInDuel(on.getUniqueId());
                boolean inFFA = plugin.getPartyFFAManager().isParticipant(on.getUniqueId());
                boolean spec = plugin.getSpectateManager().isSpectating(on.getUniqueId());
                org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
                String specSym = cfg.getString("status.spec-symbol", "&7 👁");
                String duelSym = cfg.getString("status.duel-symbol", "&c ⚔");
                if (spec) return org.bukkit.ChatColor.stripColor(
                        org.bukkit.ChatColor.translateAlternateColorCodes('&', specSym)).trim();
                if (inDuel || inFFA) return org.bukkit.ChatColor.stripColor(
                        org.bukkit.ChatColor.translateAlternateColorCodes('&', duelSym)).trim();
                return "";
            }
            case "currency": return plugin.getConfigManager().getCurrencyName();
            case "coins":  return String.valueOf(plugin.getPlayerManager().getStat(player.getUniqueId(), "coins"));
            case "wins":   return String.valueOf(plugin.getPlayerManager().getStat(player.getUniqueId(), "wins"));
            case "losses": return String.valueOf(plugin.getPlayerManager().getStat(player.getUniqueId(), "losses"));
            case "kills":  return String.valueOf(plugin.getPlayerManager().getStat(player.getUniqueId(), "kills"));
            case "deaths": return String.valueOf(plugin.getPlayerManager().getStat(player.getUniqueId(), "deaths"));
        }
        return null;
    }

    /**
     * Löst Armor-Trim-Placeholders auf:
     * <ul>
     *   <li>{@code armortrim_helmet} → "silence" (für Caller-Spieler)</li>
     *   <li>{@code material_chestplate} → "diamond"</li>
     *   <li>{@code armortrim_helmet_Steve} → Trim-Pattern von Steve</li>
     * </ul>
     * Wenn nichts gesetzt ist, gibt leeren String zurück.
     */
    private String resolveArmorTrimPlaceholder(OfflinePlayer caller, String lower) {
        if (plugin.getArmorTrimManager() == null) return "";
        boolean trimQuery = lower.startsWith("armortrim_");
        String rest = trimQuery ? lower.substring("armortrim_".length())
                                 : lower.substring("material_".length());
        // rest = "helmet" oder "helmet_steve"
        String pieceKey;
        String targetName;
        int underscore = rest.indexOf('_');
        if (underscore < 0) {
            pieceKey = rest;
            targetName = null;
        } else {
            pieceKey = rest.substring(0, underscore);
            targetName = rest.substring(underscore + 1);
        }
        dev.duels.managers.ArmorTrimManager.Piece piece;
        try {
            piece = dev.duels.managers.ArmorTrimManager.Piece.valueOf(pieceKey.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return "";
        }

        java.util.UUID uuid;
        if (targetName == null) {
            uuid = caller.getUniqueId();
        } else {
            OfflinePlayer target = org.bukkit.Bukkit.getOfflinePlayer(targetName);
            uuid = target != null ? target.getUniqueId() : null;
        }
        if (uuid == null) return "";
        return trimQuery
                ? plugin.getArmorTrimManager().getTrim(uuid, piece)
                : plugin.getArmorTrimManager().getMaterial(uuid, piece);
    }

    private static final java.util.Set<String> LEADERBOARD_CATS = new java.util.HashSet<>(
            java.util.Arrays.asList("kills", "deaths", "wins", "losses", "coins", "kd", "winrate"));

    /**
     * Löst Ranglisten-Placeholder auf: {@code <kategorie>_<platz>[_name|_value]}.
     * Liefert {@code null} wenn es kein Ranglisten-Placeholder ist (damit der
     * normale switch danach weiterläuft).
     */
    private String resolveLeaderboardPlaceholder(String lower) {
        String[] parts = lower.split("_");
        if (parts.length < 2) return null;
        String cat = parts[0];
        if (!LEADERBOARD_CATS.contains(cat)) return null;
        int rank;
        try {
            rank = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            return null;
        }
        // Default (kein Suffix) = nur der NAME des Spielers (User-Wunsch).
        //   %duels_kills_1%        → nur der Name auf Platz 1
        //   %duels_kills_1_name%   → nur der Name
        //   %duels_kills_1_value%  → nur der Wert
        //   %duels_kills_1_line%   → komplette Format-Zeile (#1 Name - Wert)
        String field = parts.length >= 3 ? parts[2] : "name";

        if (plugin.getPlayerManager() == null) return "";
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
        java.util.List<dev.duels.objects.PlayerData> lb =
                plugin.getPlayerManager().getLeaderboard(cat);

        if (rank < 1 || rank > lb.size()) {
            if ("line".equals(field)) {
                return org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        cfg.getString("leaderboard.empty", "&7---"));
            }
            return "";
        }

        dev.duels.objects.PlayerData pd = lb.get(rank - 1);
        String name = pd.getName() == null ? "Unknown" : pd.getName();
        String value = plugin.getPlayerManager().formatLeaderboardValue(pd, cat);
        if ("name".equals(field)) return name;
        if ("value".equals(field)) return value;

        String category = cfg.getString("leaderboard.names." + cat, cat);
        // Coins-Kategorie folgt dem konfigurierbaren Währungsnamen, sofern der
        // Nutzer den Leaderboard-Namen nicht manuell überschrieben hat.
        if ("coins".equals(cat) && (category == null || category.equalsIgnoreCase("Coins"))) {
            category = plugin.getConfigManager().getCurrencyName();
        }
        String fmt = cfg.getString("leaderboard.format", "&e#{rank} &f{name} &8- &a{value}");
        fmt = fmt.replace("{rank}", String.valueOf(rank))
                 .replace("{name}", name)
                 .replace("{value}", value)
                 .replace("{category}", category);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', fmt);
    }
}
