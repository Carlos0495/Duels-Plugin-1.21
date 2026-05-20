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
}
