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
        if (!mainConfig.contains("scoreboard-title")) {
            mainConfig.set("scoreboard-title", "§3§l🪓 Duels");
            dirty = true;
        }
        if (!mainConfig.contains("scoreboard-lines")) {
            mainConfig.set("scoreboard-lines", java.util.Arrays.asList(
                    "",
                    "§a☻ §7ᴏɴʟɪɴᴇ §a%online%",
                    "§6🏹 §7ɪɴ ᴅᴜᴇʟѕ §6%playing%",
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
                    "§7🛜ʏᴏᴜʀ ᴘɪɴɢ §a%playerping%",
                    "§7🛜ᴏᴘᴘᴏɴᴇɴᴛѕ ᴘɪɴɢ §c%opponentping%",
                    "",
                    "§e🧪 §7ᴛɪᴍᴇ ʟᴇꜰᴛ §e%timeleft%",
                    ""
            ));
            dirty = true;
        }
        if (!mainConfig.contains("duel-time")) { mainConfig.set("duel-time", 180); dirty = true; }
        if (!mainConfig.contains("request-timeout")) { mainConfig.set("request-timeout", 30); dirty = true; }
        if (!mainConfig.contains("default-map")) { mainConfig.set("default-map", "§cᴅᴜᴇʟѕ ᴍᴀᴘ"); dirty = true; }
        if (!mainConfig.contains("default-bestof")) { mainConfig.set("default-bestof", 1); dirty = true; }
        if (!mainConfig.contains("bestof-options")) { mainConfig.set("bestof-options", java.util.Arrays.asList(1, 3, 5, 10)); dirty = true; }
        if (!mainConfig.contains("arena.max-snapshot-blocks")) { mainConfig.set("arena.max-snapshot-blocks", 200000); dirty = true; }
        if (!mainConfig.contains("party.ffa-grace-seconds")) { mainConfig.set("party.ffa-grace-seconds", 10); dirty = true; }
        if (dirty) plugin.saveConfig();
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