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

        switch (params.toLowerCase()) {
            case "status": {
                Player on = player.getPlayer();
                if (on == null) return "";
                boolean inDuel = plugin.getDuelManager() != null
                        && plugin.getDuelManager().isInDuel(on.getUniqueId());
                boolean inFFA = plugin.getPartyFFAManager() != null
                        && plugin.getPartyFFAManager().isParticipant(on.getUniqueId());
                boolean spec = plugin.getSpectateManager() != null
                        && plugin.getSpectateManager().isSpectating(on.getUniqueId());
                if (spec) return " §7👁";
                if (inDuel || inFFA) return " §c⚔";
                return "";
            }
            case "status_icon": {
                Player on = player.getPlayer();
                if (on == null) return "";
                boolean inDuel = plugin.getDuelManager().isInDuel(on.getUniqueId());
                boolean inFFA = plugin.getPartyFFAManager().isParticipant(on.getUniqueId());
                boolean spec = plugin.getSpectateManager().isSpectating(on.getUniqueId());
                if (spec) return "👁";
                if (inDuel || inFFA) return "⚔";
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
}
