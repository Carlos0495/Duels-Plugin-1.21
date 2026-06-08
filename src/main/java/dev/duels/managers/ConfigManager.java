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
    private FileConfiguration messagesConfig;

    private File playersFile;
    private File kitsFile;
    private File arenaFile;
    private File messagesFile;

    public ConfigManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadAllConfigs() {
        // Main config — IMMER frisch von Disk laden, damit Hand-Edits
        // durch /duels reload auch wirklich übernommen werden. Ohne
        // reloadConfig() liefert plugin.getConfig() die in-memory-cached
        // Version → User-Edits wären ignoriert.
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        mainConfig = plugin.getConfig();

        // Players config
        playersFile = new File(plugin.getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            createDefaultFile(playersFile, "players.yml");
        }
        playersConfig = YamlConfiguration.loadConfiguration(playersFile);

        // Kits config — frisch von Disk
        kitsFile = new File(plugin.getDataFolder(), "kits.yml");
        if (!kitsFile.exists()) {
            createDefaultFile(kitsFile, "kits.yml");
        }
        kitsConfig = YamlConfiguration.loadConfiguration(kitsFile);

        // Arena config — frisch von Disk
        arenaFile = new File(plugin.getDataFolder(), "arena.yml");
        if (!arenaFile.exists()) {
            createDefaultFile(arenaFile, "arena.yml");
        }
        arenaConfig = YamlConfiguration.loadConfiguration(arenaFile);

        // Messages config — eigene Datei, alle Nachrichten editierbar.
        loadMessagesConfig();

        // Default Werte setzen
        setDefaults();
    }

    /**
     * Lädt {@code messages.yml}. Erstellt die Datei aus der gebündelten
     * Ressource falls nicht vorhanden und merged fehlende Keys aus der
     * Ressource nach (so bekommen bestehende Server neue Nachrichten-Keys
     * automatisch, ohne ihre Datei löschen zu müssen).
     */
    public void loadMessagesConfig() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            createDefaultFile(messagesFile, "messages.yml");
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        // Fehlende Keys aus der gebündelten Ressource nachmergen.
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                FileConfiguration bundled = YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                boolean changed = false;
                for (String key : bundled.getKeys(true)) {
                    if (bundled.isConfigurationSection(key)) continue;
                    if (!messagesConfig.contains(key)) {
                        messagesConfig.set(key, bundled.get(key));
                        changed = true;
                    }
                }
                if (changed) messagesConfig.save(messagesFile);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not merge messages.yml defaults: " + e.getMessage());
        }
    }

    public void reloadMessagesConfigFromDisk() {
        loadMessagesConfig();
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
                    "§e🪙 §7%currency% §e%coins%",
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
                lines.add(insertAt, "§e🪙 §7%currency% §e%coins%");
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
        // Queue-Welt-Whitelist: nur Spieler in diesen Welten können Queues
        // betreten. Leer = jede Welt erlaubt (mit Fallback auf Lobby-Welt).
        // Standardwert leer, damit das Plugin out-of-box wie vorher
        // funktioniert (Lobby-Welt automatisch erlaubt).
        if (!mainConfig.contains("queue.allowed-worlds")) {
            mainConfig.set("queue.allowed-worlds", new java.util.ArrayList<String>());
            dirty = true;
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
        if (!mainConfig.contains("status.lobby-symbol"))   { mainConfig.set("status.lobby-symbol", "&a ✦"); dirty = true; }
        if (!mainConfig.contains("status.world-symbol"))   { mainConfig.set("status.world-symbol", "&6 ▲"); dirty = true; }
        if (!mainConfig.contains("status.team1-symbol"))   { mainConfig.set("status.team1-symbol", "&b[T1] "); dirty = true; }
        if (!mainConfig.contains("status.team2-symbol"))   { mainConfig.set("status.team2-symbol", "&c[T2] "); dirty = true; }

        // Welt-Einschränkungen: In welchen Welten welche Aktion erlaubt ist.
        // Jeweils eine LISTE von Welt-Namen. Leere Liste = überall erlaubt.
        // Beispiel: ["world", "lobby"]
        //   join-party            – wo man einer Party beitreten kann
        //   public-party-message  – wo die öffentliche Party-Nachricht erscheint
        //   queue                 – wo man der Queue beitreten / duellieren kann
        //   kit-preview           – wo man Kits vorschauen kann
        //   kit-edit              – wo man Kits (Layouts) bearbeiten kann
        if (!mainConfig.contains("worlds.join-party"))
            { mainConfig.set("worlds.join-party", java.util.Arrays.asList("world")); dirty = true; }
        if (!mainConfig.contains("worlds.public-party-message"))
            { mainConfig.set("worlds.public-party-message", java.util.Arrays.asList("world")); dirty = true; }
        if (!mainConfig.contains("worlds.queue"))
            { mainConfig.set("worlds.queue", java.util.Arrays.asList("world")); dirty = true; }
        if (!mainConfig.contains("worlds.kit-preview"))
            { mainConfig.set("worlds.kit-preview", java.util.Arrays.asList("world")); dirty = true; }
        if (!mainConfig.contains("worlds.kit-edit"))
            { mainConfig.set("worlds.kit-edit", java.util.Arrays.asList("world")); dirty = true; }
        if (!mainConfig.contains("worlds.custom-kit"))
            { mainConfig.set("worlds.custom-kit", java.util.Arrays.asList("world")); dirty = true; }

        // Spectator-Block-Kollision: Wenn true, können Spieler die ein Match
        // zuschauen (über /spectate oder Auto-Spectate) NICHT durch Blöcke
        // (auch nicht Barrier) fliegen. Leute im normalen, offiziellen
        // SPECTATOR-GameMode (die kein Match zuschauen) sind NICHT betroffen.
        if (!mainConfig.contains("spectator.block-collision"))
            { mainConfig.set("spectator.block-collision", true); dirty = true; }

        // Anti-Glitch: verhindert dass Spieler sich durch konfigurierte Blöcke
        // glitchen (z.B. mit Ender-Pearls durch Wände). Der Pearl-Teleport wird
        // dann abgebrochen und der Spieler bleibt an seiner Position.
        //   enabled : an/aus
        //   scope   : DUELS  = nur in Duel/FFA/Team-Matches
        //             GLOBAL = überall
        //   mode    : BLACKLIST = nur die unter 'blocks' gelisteten Blöcke blocken
        //             WHITELIST = nur durch die unter 'blocks' gelisteten Blöcke
        //                         darf man durch, ALLE anderen werden geblockt
        //             ALL       = durch ALLE Blöcke wird geblockt
        //             NONE      = nichts wird geblockt (aus)
        //   blocks  : Liste von Material-Namen (für BLACKLIST/WHITELIST)
        if (!mainConfig.contains("anti-glitch.enabled"))
            { mainConfig.set("anti-glitch.enabled", true); dirty = true; }
        if (!mainConfig.contains("anti-glitch.scope"))
            { mainConfig.set("anti-glitch.scope", "DUELS"); dirty = true; }
        if (!mainConfig.contains("anti-glitch.mode"))
            { mainConfig.set("anti-glitch.mode", "BLACKLIST"); dirty = true; }
        if (!mainConfig.contains("anti-glitch.blocks"))
            { mainConfig.set("anti-glitch.blocks",
                    java.util.Arrays.asList("BARRIER", "BEDROCK")); dirty = true; }

        // Ranglisten-Placeholder (%duels_<kategorie>_<platz>%).
        //   format : Format einer kompletten Zeile. Platzhalter:
        //            {rank} {name} {value} {category}
        //   empty  : Text wenn kein Spieler auf dem Platz existiert
        //   <kategorie>-name : Anzeigename der Kategorie für {category}
        if (!mainConfig.contains("leaderboard.format"))
            { mainConfig.set("leaderboard.format", "&e#{rank} &f{name} &8- &a{value}"); dirty = true; }
        if (!mainConfig.contains("leaderboard.empty"))
            { mainConfig.set("leaderboard.empty", "&7---"); dirty = true; }
        if (!mainConfig.contains("leaderboard.names.kills"))   { mainConfig.set("leaderboard.names.kills", "Kills"); dirty = true; }
        if (!mainConfig.contains("leaderboard.names.deaths"))  { mainConfig.set("leaderboard.names.deaths", "Deaths"); dirty = true; }
        if (!mainConfig.contains("leaderboard.names.wins"))    { mainConfig.set("leaderboard.names.wins", "Wins"); dirty = true; }
        if (!mainConfig.contains("leaderboard.names.losses"))  { mainConfig.set("leaderboard.names.losses", "Losses"); dirty = true; }
        if (!mainConfig.contains("leaderboard.names.coins"))   { mainConfig.set("leaderboard.names.coins", "Coins"); dirty = true; }
        if (!mainConfig.contains("leaderboard.names.kd"))      { mainConfig.set("leaderboard.names.kd", "K/D"); dirty = true; }
        if (!mainConfig.contains("leaderboard.names.winrate")) { mainConfig.set("leaderboard.names.winrate", "Win Rate"); dirty = true; }

        // Per-Duel Chat-Filter. Wenn aktiv, sehen Spieler in einem Duel/FFA/
        // Team-Match nur noch den Chat ihrer Match-Gegner/Mitspieler; ihre
        // eigenen Nachrichten gehen ebenfalls nur ans Match. So bleibt der
        // globale Chat sauber.
        //   duel.chat-isolated : an/aus für laufende Matches (default an)
        // Pro-Welt eigener (isolierter) Chat: Spieler in diesen Welten chatten
        // nur mit anderen Spielern in DERSELBEN Welt.
        //   chat.per-world-worlds : Liste von Welt-Namen mit isoliertem Chat
        //   chat.lobby-isolated   : eigener Chat nur für die Lobby-Welt(en)
        if (!mainConfig.contains("chat.duel-isolated"))
            { mainConfig.set("chat.duel-isolated", true); dirty = true; }
        if (!mainConfig.contains("chat.lobby-isolated"))
            { mainConfig.set("chat.lobby-isolated", false); dirty = true; }
        if (!mainConfig.contains("chat.per-world-worlds"))
            { mainConfig.set("chat.per-world-worlds", new java.util.ArrayList<String>()); dirty = true; }

        // Tablist-Filter: wer im Duel/FFA/Team ist, sieht im TAB nur seine
        // Gegner/Mitspieler. Default AUS.
        //   tablist.duel-filter      : an/aus (default aus)
        //   tablist.per-world-worlds : in diesen Welten sieht man nur Spieler
        //                              derselben Welt im TAB
        if (!mainConfig.contains("tablist.duel-filter"))
            { mainConfig.set("tablist.duel-filter", false); dirty = true; }
        if (!mainConfig.contains("tablist.per-world-worlds"))
            { mainConfig.set("tablist.per-world-worlds", new java.util.ArrayList<String>()); dirty = true; }

        // Währungsname: was statt "Coins" überall angezeigt wird (Scoreboard,
        // Vergleich, Nachrichten via {currency}, Placeholder %duels_currency%,
        // Leaderboard-Kategorie). Beliebiger Text möglich (z.B. "Elo").
        if (!mainConfig.contains("currency.name"))
            { mainConfig.set("currency.name", "Coins"); dirty = true; }

        // Auto-Disable bei fehlender Permission (config-toggle):
        //   fly      : kein duels.fly → Fly automatisch aus
        //   armortrim: kein Armortrim-Recht → Trims werden entfernt
        if (!mainConfig.contains("permissions.auto-disable-fly"))
            { mainConfig.set("permissions.auto-disable-fly", true); dirty = true; }
        if (!mainConfig.contains("permissions.auto-disable-armortrim"))
            { mainConfig.set("permissions.auto-disable-armortrim", true); dirty = true; }

        // Duel-Einladung: ob die Map/Arena in der Benachrichtigung steht.
        if (!mainConfig.contains("duel.show-map-in-request"))
            { mainConfig.set("duel.show-map-in-request", true); dirty = true; }

        // Custom-Kit-System Defaults
        if (!mainConfig.contains("custom-kits.enabled"))
            { mainConfig.set("custom-kits.enabled", true); dirty = true; }
        if (!mainConfig.contains("custom-kits.tiers.tier1.permission"))
            { mainConfig.set("custom-kits.tiers.tier1.permission", "duels.customkit.tier1"); dirty = true; }
        if (!mainConfig.contains("custom-kits.tiers.tier1.limit"))
            { mainConfig.set("custom-kits.tiers.tier1.limit", 2); dirty = true; }
        if (!mainConfig.contains("custom-kits.tiers.tier2.permission"))
            { mainConfig.set("custom-kits.tiers.tier2.permission", "duels.customkit.tier2"); dirty = true; }
        if (!mainConfig.contains("custom-kits.tiers.tier2.limit"))
            { mainConfig.set("custom-kits.tiers.tier2.limit", 4); dirty = true; }

        if (dirty) plugin.saveConfig();
    }

    /** @return konfigurierbarer Währungsname (default "Coins"). */
    public String getCurrencyName() {
        if (mainConfig == null) return "Coins";
        String s = mainConfig.getString("currency.name", "Coins");
        return (s == null || s.trim().isEmpty()) ? "Coins" : s;
    }

    /** @return true wenn Fly bei fehlender Permission automatisch aus ist. */
    public boolean isAutoDisableFly() {
        return mainConfig != null && mainConfig.getBoolean("permissions.auto-disable-fly", true);
    }

    /** @return true wenn Armortrims bei fehlender Permission entfernt werden. */
    public boolean isAutoDisableArmortrim() {
        return mainConfig != null && mainConfig.getBoolean("permissions.auto-disable-armortrim", true);
    }

    /** @return true wenn die Map in der Duel-Einladung angezeigt wird. */
    public boolean isShowMapInRequest() {
        return mainConfig == null || mainConfig.getBoolean("duel.show-map-in-request", true);
    }

    /** @return true wenn Match-Spectator-Block-Kollision aktiv ist. */
    public boolean isSpectatorBlockCollision() {
        return mainConfig == null || mainConfig.getBoolean("spectator.block-collision", true);
    }

    // ---- Chat-Filter ----

    /** @return true wenn Chat in laufenden Matches isoliert sein soll. */
    public boolean isDuelChatIsolated() {
        return mainConfig != null && mainConfig.getBoolean("chat.duel-isolated", true);
    }

    /** @return true wenn die Lobby-Welt(en) eigenen, isolierten Chat haben. */
    public boolean isLobbyChatIsolated() {
        return mainConfig != null && mainConfig.getBoolean("chat.lobby-isolated", false);
    }

    /** @return Welt-Namen (lowercase) mit eigenem, isoliertem Chat. */
    public java.util.Set<String> getPerWorldChatWorlds() {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (mainConfig != null) {
            for (String w : mainConfig.getStringList("chat.per-world-worlds")) {
                if (w != null && !w.trim().isEmpty()) set.add(w.trim().toLowerCase());
            }
        }
        return set;
    }

    // ---- Tablist-Filter ----

    /** @return true wenn TAB im Match nur Gegner/Mitspieler zeigt (default aus). */
    public boolean isDuelTablistFilter() {
        return mainConfig != null && mainConfig.getBoolean("tablist.duel-filter", false);
    }

    /** @return Welt-Namen (lowercase) mit eigenem, gefiltertem TAB. */
    public java.util.Set<String> getPerWorldTablistWorlds() {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (mainConfig != null) {
            for (String w : mainConfig.getStringList("tablist.per-world-worlds")) {
                if (w != null && !w.trim().isEmpty()) set.add(w.trim().toLowerCase());
            }
        }
        return set;
    }

    public boolean isAntiGlitchEnabled() {
        if (mainConfig == null) return false;
        if (!mainConfig.getBoolean("anti-glitch.enabled", true)) return false;
        String mode = mainConfig.getString("anti-glitch.mode", "BLACKLIST");
        return !"NONE".equalsIgnoreCase(mode);
    }

    /** @return "GLOBAL" oder "DUELS". */
    public String getAntiGlitchScope() {
        if (mainConfig == null) return "DUELS";
        return mainConfig.getString("anti-glitch.scope", "DUELS");
    }

    /**
     * Prüft ob man durch das angegebene Material laut Anti-Glitch-Config
     * NICHT hindurch darf (geblockt). Berücksichtigt mode + blocks.
     */
    public boolean isAntiGlitchBlocked(org.bukkit.Material material) {
        if (material == null || mainConfig == null) return false;
        String mode = mainConfig.getString("anti-glitch.mode", "BLACKLIST");
        if (mode == null) mode = "BLACKLIST";
        switch (mode.toUpperCase()) {
            case "NONE": return false;
            case "ALL":  return true;
            case "WHITELIST": {
                // Nur durch gelistete Blöcke erlaubt → alle anderen geblockt.
                java.util.List<String> list = mainConfig.getStringList("anti-glitch.blocks");
                for (String s : list) {
                    if (s != null && s.equalsIgnoreCase(material.name())) return false;
                }
                return true;
            }
            case "BLACKLIST":
            default: {
                java.util.List<String> list = mainConfig.getStringList("anti-glitch.blocks");
                for (String s : list) {
                    if (s != null && s.equalsIgnoreCase(material.name())) return true;
                }
                return false;
            }
        }
    }

    /**
     * Prüft ob die angegebene Aktion in der aktuellen Welt des Spielers
     * erlaubt ist. Liest {@code worlds.<action>} als Liste von Welt-Namen.
     * Leere/fehlende Liste = überall erlaubt.
     */
    public boolean isWorldAllowed(org.bukkit.entity.Player player, String action) {
        if (player == null) return false;
        if (mainConfig == null) return true;
        java.util.List<String> allowed = mainConfig.getStringList("worlds." + action);
        if (allowed == null || allowed.isEmpty()) return true;
        String world = player.getWorld() != null ? player.getWorld().getName() : "";
        for (String w : allowed) {
            if (w != null && w.equalsIgnoreCase(world)) return true;
        }
        return false;
    }

    /**
     * Liest eine konfigurierbare Message. Reihenfolge: zuerst
     * {@code messages.yml} (Key = path), dann {@code config.yml}
     * ({@code messages.<path>}) für Abwärtskompatibilität, sonst der
     * übergebene Fallback. Unterstützt &amp;-Farbcodes und {@code {key}}
     * Platzhalter aus der Map.
     */
    public String getMessage(String path, String fallback, java.util.Map<String, String> placeholders) {
        String raw = null;
        if (messagesConfig != null) raw = messagesConfig.getString(path);
        if (raw == null && mainConfig != null) raw = mainConfig.getString("messages." + path);
        if (raw == null) raw = fallback != null ? fallback : "";
        if (placeholders != null) {
            for (java.util.Map.Entry<String, String> e : placeholders.entrySet()) {
                raw = raw.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        // Globaler {currency}-Platzhalter (konfigurierbarer Währungsname,
        // z.B. "Coins" oder "Elo") — in JEDER Nachricht verfügbar.
        if (raw.indexOf("{currency}") >= 0) {
            raw = raw.replace("{currency}", getCurrencyName());
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', raw);
    }

    public String getMessage(String path, String fallback) {
        return getMessage(path, fallback, null);
    }

    /** Wie {@link #getMessage}, aber mit vorangestelltem Plugin-Prefix. */
    public String prefixed(String path, String fallback, java.util.Map<String, String> placeholders) {
        return plugin.getPrefix() + getMessage(path, fallback, placeholders);
    }

    public String prefixed(String path, String fallback) {
        return prefixed(path, fallback, null);
    }

    public FileConfiguration getMessagesConfig() { return messagesConfig; }

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