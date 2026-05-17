package dev.duels.managers;

import dev.duels.DuelsPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public class ConfigManager {

    private final DuelsPlugin plugin;
    private FileConfiguration mainConfig;
    private FileConfiguration playersConfig;
    private FileConfiguration kitsConfig;
    private FileConfiguration arenaConfig;

    private File playersFile;
    private File kitsFile;
    private File arenaFile;

    public ConfigManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadAllConfigs() {
        // Main config
        plugin.saveDefaultConfig();
        mainConfig = plugin.getConfig();

        // Players config
        playersFile = new File(plugin.getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            createDefaultFile(playersFile, "players.yml");
        }
        playersConfig = YamlConfiguration.loadConfiguration(playersFile);

        // Kits config
        kitsFile = new File(plugin.getDataFolder(), "kits.yml");
        if (!kitsFile.exists()) {
            createDefaultFile(kitsFile, "kits.yml");
        }
        kitsConfig = YamlConfiguration.loadConfiguration(kitsFile);

        // Arena config
        arenaFile = new File(plugin.getDataFolder(), "arena.yml");
        if (!arenaFile.exists()) {
            createDefaultFile(arenaFile, "arena.yml");
        }
        arenaConfig = YamlConfiguration.loadConfiguration(arenaFile);

        // Default Werte setzen
        setDefaults();
    }
    public void reloadPlayersConfig() {
        if (playersFile == null) {
            playersFile = new File(plugin.getDataFolder(), "players.yml");
        }
        if (!playersFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                playersFile.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().severe("Could not create players.yml: " + e.getMessage());
            }
        }
        playersConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(playersFile);
    }


    private void createDefaultFile(File file, String resourceName) {
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

            InputStream in = plugin.getResource(resourceName);
            if (in != null) {
                Files.copy(in, file.toPath());
            } else {
                file.createNewFile();
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not create " + resourceName + "!");
        }
    }

    /**
     * Schreibt Default-Werte einzeln und nur wenn der Key fehlt (per-Key-
     * Guard). Speichert nur dann zurück, wenn wirklich was hinzugefügt
     * wurde. So überleben User-Edits an einzelnen Keys den Plugin-Neustart.
     */
    private void setDefaults() {
        boolean dirty = false;
        if (!mainConfig.contains("prefix")) {
            // Konfigurierbares Plugin-Prefix (vorne bei jedem Plugin-Befehl).
            // Verwende '&' für Farbcodes. User kann ihn in config.yml anpassen.
            mainConfig.set("prefix", "&9&lᴅᴜᴇʟѕ &8| &7");
            dirty = true;
        }
        if (!mainConfig.contains("scoreboard-title")) {
            mainConfig.set("scoreboard-title", "§3§l🪓 Duels");
            dirty = true;
        }
        if (!mainConfig.contains("scoreboard-lines")) {
            mainConfig.set("scoreboard-lines", java.util.Arrays.asList(
                    "",
                    "§a☻ §7ᴏɴʟɪɴᴇ §a%online%",
                    "§6🏹 §7ɪɴ ᴅᴜᴇʟѕ §6%playing%",
                    "§e🪙 §7ᴄᴏɪɴѕ §e%coins%",
                    "",
                    "§2🗡 §7ᴋɪʟʟѕ §2%kills%",
                    "§c☠ §7ᴅᴇᴀᴛʜѕ §c%deaths%",
                    "§e❤ §7ᴋᴅ §e%kd%",
                    "",
                    "§a✔ §7ᴡɪɴѕ §a%wins%",
                    "§4✘ §7ʟᴏѕѕᴇѕ §4%losses%",
                    "§b🧪 §7ᴡɪɴ ʀᴀᴛᴇ §b%winrate%",
                    ""
            ));
            dirty = true;
        } else {
            // Migration: existierende Config kriegt %coins%-Zeile als 3. Eintrag
            // (unter "in duels"), falls noch nicht vorhanden.
            java.util.List<String> lines = new java.util.ArrayList<>(mainConfig.getStringList("scoreboard-lines"));
            boolean hasCoins = false;
            for (String l : lines) if (l != null && l.contains("%coins%")) { hasCoins = true; break; }
            if (!hasCoins) {
                int insertAt = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i) != null && lines.get(i).contains("%playing%")) { insertAt = i + 1; break; }
                }
                if (insertAt < 0) insertAt = Math.min(3, lines.size());
                lines.add(insertAt, "§e🪙 §7ᴄᴏɪɴѕ §e%coins%");
                mainConfig.set("scoreboard-lines", lines);
                dirty = true;
            }
        }
        if (!mainConfig.contains("duel-scoreboard-lines")) {
            mainConfig.set("duel-scoreboard-lines", java.util.Arrays.asList(
                    "",
                    "§a☻ §7ᴏɴʟɪɴᴇ §a%online%",
                    "§6🏹 §7ɪɴ ᴅᴜᴇʟѕ §6%playing%",
                    "",
                    "§4🔥 §7ᴏᴘᴘᴏɴᴇɴᴛ §4%opponent%",
                    "§b🗺 §7ᴍᴀᴘ §b%map%",
                    "",
                    "§d§l⚔ §7ʀᴏᴜɴᴅ §d%round%§7/§d%bestof%",
                    "§d§l⚔ §7sᴄᴏʀᴇ §d%score% §8(ꜰɪʀsᴛ ᴛᴏ §f%requiredwins%§8)",
                    "",
                    "§7🛜ʏᴏᴜʀ ᴘɪɴɢ §a%playerping%",
                    "§7🛜ᴏᴘᴘᴏɴᴇɴᴛѕ ᴘɪɴɢ §c%opponentping%",
                    "",
                    "§e🧪 §7ᴛɪᴍᴇ ʟᴇꜰᴛ §e%timeleft%",
                    ""
            ));
            dirty = true;
        } else {
            // Migration: bestehende duel-scoreboard-lines bekommen Round +
            // Score Zeilen falls noch nicht vorhanden (nach %map%).
            java.util.List<String> lines = new java.util.ArrayList<>(mainConfig.getStringList("duel-scoreboard-lines"));
            boolean hasRound = false;
            for (String l : lines) if (l != null && (l.contains("%bestof%") || l.contains("%round%"))) { hasRound = true; break; }
            if (!hasRound) {
                int insertAt = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i) != null && lines.get(i).contains("%map%")) { insertAt = i + 1; break; }
                }
                if (insertAt < 0) insertAt = Math.min(5, lines.size());
                lines.add(insertAt, "");
                lines.add(insertAt + 1, "§d§l⚔ §7ʀᴏᴜɴᴅ §d%round%§7/§d%bestof%");
                lines.add(insertAt + 2, "§d§l⚔ §7sᴄᴏʀᴇ §d%score% §8(ꜰɪʀsᴛ ᴛᴏ §f%requiredwins%§8)");
                mainConfig.set("duel-scoreboard-lines", lines);
                dirty = true;
            }
        }
        if (!mainConfig.contains("duel-time")) { mainConfig.set("duel-time", 180); dirty = true; }
        if (!mainConfig.contains("request-timeout")) { mainConfig.set("request-timeout", 30); dirty = true; }
        if (!mainConfig.contains("default-map")) { mainConfig.set("default-map", "§cᴅᴜᴇʟѕ ᴍᴀᴘ"); dirty = true; }
        if (!mainConfig.contains("default-bestof")) { mainConfig.set("default-bestof", 1); dirty = true; }
        if (!mainConfig.contains("bestof-options")) { mainConfig.set("bestof-options", java.util.Arrays.asList(1, 3, 5, 10)); dirty = true; }
        if (!mainConfig.contains("arena.max-snapshot-blocks")) { mainConfig.set("arena.max-snapshot-blocks", 200000); dirty = true; }
        if (!mainConfig.contains("party.ffa-grace-seconds")) { mainConfig.set("party.ffa-grace-seconds", 10); dirty = true; }
        if (!mainConfig.contains("coins.win-reward")) { mainConfig.set("coins.win-reward", 10); dirty = true; }
        if (!mainConfig.contains("duel-request-timeout-seconds")) { mainConfig.set("duel-request-timeout-seconds", 30); dirty = true; }

        // ---- Konfigurierbare Messages ----
        // Alle Texte unter `messages.*` lassen sich in der config.yml frei
        // umschreiben. Verfügbare Platzhalter sind pro Kontext unterschiedlich
        // — die Defaults zeigen, welche möglich sind.
        if (!mainConfig.contains("messages.duel.win-title"))      { mainConfig.set("messages.duel.win-title", "&a&lVICTORY"); dirty = true; }
        if (!mainConfig.contains("messages.duel.win-subtitle"))   { mainConfig.set("messages.duel.win-subtitle", "&7{score}"); dirty = true; }
        if (!mainConfig.contains("messages.duel.lose-title"))     { mainConfig.set("messages.duel.lose-title", "&c&lDEFEAT"); dirty = true; }
        if (!mainConfig.contains("messages.duel.lose-subtitle"))  { mainConfig.set("messages.duel.lose-subtitle", "&7{score}"); dirty = true; }
        if (!mainConfig.contains("messages.duel.round-lost-title"))    { mainConfig.set("messages.duel.round-lost-title", "&c&lRound lost"); dirty = true; }
        if (!mainConfig.contains("messages.duel.round-lost-subtitle")) { mainConfig.set("messages.duel.round-lost-subtitle", "&7{winner} &7won round &f{round}"); dirty = true; }
        if (!mainConfig.contains("messages.duel.countdown-title"))     { mainConfig.set("messages.duel.countdown-title", "&c{seconds}"); dirty = true; }
        if (!mainConfig.contains("messages.duel.fight-title"))         { mainConfig.set("messages.duel.fight-title", "&a&lFIGHT!"); dirty = true; }
        if (!mainConfig.contains("messages.team.win-title"))      { mainConfig.set("messages.team.win-title", "&a&lTEAM VICTORY"); dirty = true; }
        if (!mainConfig.contains("messages.team.win-subtitle"))   { mainConfig.set("messages.team.win-subtitle", "&7{team} &7wins!"); dirty = true; }
        if (!mainConfig.contains("messages.team.lose-title"))     { mainConfig.set("messages.team.lose-title", "&c&lDEFEAT"); dirty = true; }
        if (!mainConfig.contains("messages.team.lose-subtitle"))  { mainConfig.set("messages.team.lose-subtitle", "&7{team} &7won"); dirty = true; }
        if (!mainConfig.contains("messages.ffa.win-title"))       { mainConfig.set("messages.ffa.win-title", "&a&lFFA VICTORY"); dirty = true; }
        if (!mainConfig.contains("messages.ffa.win-subtitle"))    { mainConfig.set("messages.ffa.win-subtitle", "&7You won the FFA!"); dirty = true; }
        if (!mainConfig.contains("messages.ffa.lose-title"))      { mainConfig.set("messages.ffa.lose-title", "&c&lDEFEAT"); dirty = true; }
        if (!mainConfig.contains("messages.ffa.lose-subtitle"))   { mainConfig.set("messages.ffa.lose-subtitle", "&7{winner} &7won the FFA"); dirty = true; }

        // Symbole für Status-Suffix (PlaceholderAPI %duels_status% + Nametag).
        // Reihenfolge der Priorität (wenn mehrere Bedingungen zutreffen):
        //   spec  > duel  > lobby > world
        // - duel-symbol:  Spieler ist im Duel/FFA/Team
        // - spec-symbol:  Spieler spectatet
        // - lobby-symbol: Spieler ist in der Lobby-Welt (nicht im Duel/Spec)
        // - world-symbol: Spieler ist in irgendeiner anderen Welt
        // - team1/team2:  nur für Nametag (überm Kopf) in Team-Match
        if (!mainConfig.contains("status.duel-symbol"))    { mainConfig.set("status.duel-symbol", "&c ⚔"); dirty = true; }
        if (!mainConfig.contains("status.spec-symbol"))    { mainConfig.set("status.spec-symbol", "&7 👁"); dirty = true; }
        if (!mainConfig.contains("status.lobby-symbol"))   { mainConfig.set("status.lobby-symbol", ""); dirty = true; }
        if (!mainConfig.contains("status.world-symbol"))   { mainConfig.set("status.world-symbol", ""); dirty = true; }
        if (!mainConfig.contains("status.team1-symbol"))   { mainConfig.set("status.team1-symbol", "&b[T1] "); dirty = true; }
        if (!mainConfig.contains("status.team2-symbol"))   { mainConfig.set("status.team2-symbol", "&c[T2] "); dirty = true; }

        if (dirty) plugin.saveConfig();
    }

    /**
     * Liest eine konfigurierbare Message aus der config.yml. Unterstützt
     * &amp;-Farbcodes (werden zu §) und einfache Platzhalter
     * ({@code {key}}) aus der übergebenen Map.
     */
    public String getMessage(String path, String fallback, java.util.Map<String, String> placeholders) {
        String raw = mainConfig.getString("messages." + path, fallback);
        if (raw == null) raw = fallback != null ? fallback : "";
        if (placeholders != null) {
            for (java.util.Map.Entry<String, String> e : placeholders.entrySet()) {
                raw = raw.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', raw);
    }

    public String getMessage(String path, String fallback) {
        return getMessage(path, fallback, null);
    }

    /**
     * Wird beim Plugin-Disable aufgerufen. Wir speichern hier ausschließlich
     * {@code players.yml}, weil dort Stat- und Layout-Updates rein im RAM
     * landen und sonst verloren gingen.
     *
     * <p>Bewusst NICHT mehr gespeichert werden:
     * <ul>
     *     <li>{@code config.yml} – jede Änderung in dieser Datei wird sofort
     *     beim Setzen via {@code plugin.saveConfig()} persistiert. Ein
     *     Blanket-Save am Ende würde Hand-Edits, die der Admin im laufenden
     *     Betrieb in der Datei gemacht hat, mit dem alten In-Memory-Stand
     *     überschreiben (Hauptursache für den "Config-Reset"-Bug).</li>
     *     <li>{@code kits.yml} / {@code arena.yml} – werden ebenfalls direkt
     *     geschrieben, wenn der Admin per Command etwas ändert.</li>
     * </ul>
     */
    public void saveAllConfigs() {
        savePlayersConfig();
    }

    /**
     * Lädt {@code config.yml} frisch von Disk in den Speicher. WICHTIG vor
     * jedem Schreib-Pfad, der nur einzelne Keys ändert (z.B. /setspawn).
     * Sonst hätten Hand-Edits, die der Admin direkt in der Datei gemacht
     * hat, eine Race mit dem stale In-Memory-Zustand verloren.
     */
    public void reloadMainConfigFromDisk() {
        plugin.reloadConfig();
        this.mainConfig = plugin.getConfig();
    }

    public void reloadKitsConfigFromDisk() {
        if (kitsFile == null) return;
        this.kitsConfig = YamlConfiguration.loadConfiguration(kitsFile);
    }

    public void reloadArenaConfigFromDisk() {
        if (arenaFile == null) return;
        this.arenaConfig = YamlConfiguration.loadConfiguration(arenaFile);
    }

    public void savePlayersConfig() {
        try {
            playersConfig.save(playersFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not save players.yml: " + e.getMessage());
        }
    }


    public void saveKitsConfig() {
        try {
            kitsConfig.save(kitsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save kits config!");
        }
    }

    public void saveArenaConfig() {
        try {
            arenaConfig.save(arenaFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save arena config!");
        }
    }

    // Getter
    public FileConfiguration getMainConfig() { return mainConfig; }
    public FileConfiguration getPlayersConfig() { return playersConfig; }
    public FileConfiguration getKitsConfig() { return kitsConfig; }
    public FileConfiguration getArenaConfig() { return arenaConfig; }
}