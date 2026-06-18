package dev.duels.placeholders;

import dev.duels.DuelsPlugin;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.placeholder.PlaceholderManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Direkter Hook in das NEZNAMY-TAB-Plugin: registriert {@code %duels_status%}
 * (und Kit-Counter) als TAB-Player-Placeholder. So wertet TAB unsere
 * Symbole direkt aus — die PAPI-Auto-Discovery durch TAB war im User-Setup
 * unzuverlässig (TAB-Format zeigte die LP-Prefix + Name korrekt, aber
 * %duels_status% blieb leer obwohl {@code /papi parse me %duels_status%}
 * korrekt funktionierte).
 *
 * <p>Diese Klasse referenziert TAB-API-Klassen direkt. Sie darf nur
 * geladen werden, wenn das TAB-Plugin auf dem Server installiert ist —
 * Aufrufer müssen {@code Bukkit.getPluginManager().getPlugin("TAB") != null}
 * vorher prüfen.</p>
 */
public final class TabHook {

    private TabHook() {}

    /** Registriert die TAB-Placeholders. Refresh-Intervall MUSS durch 50 teilbar sein. */
    public static void register(DuelsPlugin plugin) {
        PlaceholderManager pm = TabAPI.getInstance().getPlaceholderManager();

        // %duels_status% — pro Spieler unterschiedlich, 500 ms Refresh.
        pm.registerPlayerPlaceholder("%duels_status%", 500, tabPlayer -> resolveStatus(plugin, tabPlayer));

        // %duels_playing_<kit>% / %duels_queue_<kit>% wären besser via Pattern,
        // aber dafür müsste man jeden Kit einzeln registrieren oder die
        // Pattern-API nutzen — verzichten wir vorerst, TAB greift dafür auf
        // PAPI-Fallback zurück.

        plugin.getLogger().info("Registered TAB placeholder '%duels_status%' (refresh 500ms).");
    }

    /** Entregistriert die TAB-Placeholders (z.B. bei Plugin-Reload). */
    public static void unregister() {
        try {
            TabAPI.getInstance().getPlaceholderManager().unregisterPlaceholder("%duels_status%");
        } catch (Throwable ignored) {}
    }

    private static String resolveStatus(DuelsPlugin plugin, TabPlayer tabPlayer) {
        UUID uuid = tabPlayer.getUniqueId();
        Player on = Bukkit.getPlayer(uuid);
        if (on == null) return "";

        FileConfiguration cfg = plugin.getConfig();

        boolean spec = plugin.getSpectateManager() != null
                && plugin.getSpectateManager().isSpectating(uuid);
        if (spec) {
            return ChatColor.translateAlternateColorCodes('&',
                    cfg.getString("status.spec-symbol", "&7 👁"));
        }

        boolean inDuel = plugin.getDuelManager() != null
                && plugin.getDuelManager().isInDuel(uuid);
        boolean inFFA = plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(uuid);
        if (inDuel || inFFA) {
            return ChatColor.translateAlternateColorCodes('&',
                    cfg.getString("status.duel-symbol", "&c ⚔"));
        }

        boolean inLobby = plugin.getPlayerManager() != null
                && plugin.getPlayerManager().isInLobbyWorld(on);
        String key = inLobby ? "status.lobby-symbol" : "status.world-symbol";
        String defaultVal = inLobby ? "&a ✦" : "&6 ▲";
        return ChatColor.translateAlternateColorCodes('&', cfg.getString(key, defaultVal));
    }
}
