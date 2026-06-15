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
        // Button für ALLE sichtbar (auch ohne Permission)? false = nur mit Permission.
        if (!mainConfig.contains("custom-kits.show-button"))
            { mainConfig.set("custom-kits.show-button", true); dirty = true; }
        // Block-Break Precedence für NORMALE Kits: MERGE | ARENA | KIT.
        if (!mainConfig.contains("custom-kits.block-rules.precedence"))
            { mainConfig.set("custom-kits.block-rules.precedence", "MERGE"); dirty = true; }
        // tier0 = 1 Kit
        if (!mainConfig.contains("custom-kits.tiers.tier0.permission"))
            { mainConfig.set("custom-kits.tiers.tier0.permission", "duels.customkit.tier0"); dirty = true; }
        if (!mainConfig.contains("custom-kits.tiers.tier0.limit"))
            { mainConfig.set("custom-kits.tiers.tier0.limit", 1); dirty = true; }
        if (!mainConfig.contains("custom-kits.tiers.tier1.permission"))
            { mainConfig.set("custom-kits.tiers.tier1.permission", "duels.customkit.tier1"); dirty = true; }
        if (!mainConfig.contains("custom-kits.tiers.tier1.limit"))
            { mainConfig.set("custom-kits.tiers.tier1.limit", 2); dirty = true; }
        if (!mainConfig.contains("custom-kits.tiers.tier2.permission"))
            { mainConfig.set("custom-kits.tiers.tier2.permission", "duels.customkit.tier2"); dirty = true; }
        if (!mainConfig.contains("custom-kits.tiers.tier2.limit"))
            { mainConfig.set("custom-kits.tiers.tier2.limit", 4); dirty = true; }

        // Kit-Auswahl-GUI: feste Slots pro Kit.
        //   use-custom-slots: false → Kits werden automatisch zentriert
        //                             angeordnet (Standard, wie bisher).
        //   use-custom-slots: true  → Kits mit gesetztem 'gui-slot' (in
        //                             kits.yml) erscheinen genau in diesem Slot
        //                             (0-53). Kits ohne gui-slot füllen die
        //                             freien Standard-Positionen auf.
        if (!mainConfig.contains("kits.use-custom-slots"))
            { mainConfig.set("kits.use-custom-slots", false); dirty = true; }

        // Liquid-Sweep beim Arena-Reset: Obergrenze an Blöcken, die zur
        // Sicherheit auf Wasser/Lava geprüft werden (Brute-Force-Reset).
        if (!mainConfig.contains("arena.max-liquid-sweep-blocks"))
            { mainConfig.set("arena.max-liquid-sweep-blocks", 1000000); dirty = true; }

        // Erklärende Kommentare in die config.yml schreiben (einmalig pro
        // Comments-Version), damit jeder versteht wofür die Optionen sind.
        if (applyComments()) dirty = true;

        if (dirty) plugin.saveConfig();
    }

    /**
     * Schreibt erklärende Kommentar-Blöcke über die config-Keys, damit Admins
     * direkt in der config.yml sehen, wofür jede Option ist und welche Werte
     * möglich sind. Läuft nur, wenn sich die {@code config-comments-version}
     * geändert hat, um die Datei nicht bei jedem Start neu zu schreiben.
     *
     * @return true, wenn Kommentare (neu) gesetzt wurden und gespeichert werden muss.
     */
    private boolean applyComments() {
        final int CURRENT = 1;
        if (mainConfig.getInt("config-comments-version", 0) >= CURRENT) return false;

        c("prefix",
                "Plugin-Prefix vor jeder Plugin-Nachricht. '&' fuer Farbcodes.");
        c("scoreboard-title",
                "Titel des Lobby-Scoreboards.");
        c("scoreboard-lines",
                "Lobby-Scoreboard-Zeilen.",
                "Platzhalter: %online% %playing% %currency% %coins% %kills%",
                "%deaths% %kd% %wins% %losses% %winrate%");
        c("duel-scoreboard-lines",
                "Scoreboard waehrend eines 1v1-Duells.",
                "Platzhalter: %opponent% %map% %round% %bestof% %score%",
                "%requiredwins% %playerping% %opponentping% %timeleft%");
        c("ffa-scoreboard-lines",
                "Scoreboard waehrend eines FFA-Matches.",
                "Platzhalter: %alive% %map% %timeleft% (+ Lobby-Platzhalter)");
        c("team-scoreboard-lines",
                "Scoreboard waehrend eines Team-Fights.",
                "Platzhalter: %yourteam% %enemyteam% %map% %timeleft%");
        c("queue",
                "Queue-Einstellungen.",
                "allowed-worlds: Welten in denen man die Queue betreten darf.",
                "Leere Liste [] = jede Welt (Lobby-Welt automatisch erlaubt).");
        c("duel-time",
                "Standard-Dauer eines Duells in Sekunden (Kit kann ueberschreiben).");
        c("request-timeout",
                "Zeit in Sekunden, bis eine Duel-Anfrage ablaeuft.");
        c("default-map",
                "Standard-Map-Name (Anzeige), wenn keine Arena einen Namen setzt.");
        c("default-bestof",
                "Standard 'Best of' (Anzahl Runden) fuer ein Duell.");
        c("bestof-options",
                "Auswaehlbare 'Best of' Werte im Best-Of-Menue.");
        c("arena",
                "Arena-Reset Einstellungen.",
                "max-snapshot-blocks: max. Bloecke pro Arena-Snapshot.",
                "max-liquid-sweep-blocks: Obergrenze fuer den Fluessigkeits-",
                "Brute-Force-Reset (Wasser/Lava sicher entfernen).");
        c("party",
                "Party-Einstellungen.",
                "ffa-grace-seconds: Schonzeit (Sek.) zu FFA-Start ohne Schaden.");
        c("coins",
                "Belohnungen in der konfigurierbaren Waehrung (siehe currency.name).",
                "win-reward: wie viel man pro Sieg bekommt.");
        c("duel-request-timeout-seconds",
                "Timeout (Sek.) einer Duel-Anfrage (alternativer Schluessel).");
        c("messages",
                "Titel/Untertitel (Title-Animationen) fuer Sieg/Niederlage usw.",
                "ALLE Chat-Texte stehen separat in der messages.yml!",
                "Platzhalter: {score} {winner} {round} {team} {seconds} {currency}");
        c("status",
                "Status-Symbole (PlaceholderAPI %duels_status% + Nametag).",
                "Prioritaet: spec > duel > lobby > world.",
                "team1/team2: nur fuer den Nametag im Team-Match.");
        c("worlds",
                "Welt-Einschraenkungen: je eine LISTE von Welt-Namen.",
                "Leere Liste [] = ueberall erlaubt. Beispiel: [\"world\", \"lobby\"]",
                "Item-Ersetzungen (Hotbar, Party, Visibility) passieren IMMER",
                "nur in der Lobby-Welt (wo /setspawn gesetzt wurde).",
                "  join-party           - wo man einer Party beitreten kann",
                "  public-party-message - wo die oeffentliche Party-Nachricht erscheint",
                "  queue                - wo man Queue/Duell starten kann",
                "  kit-preview          - wo man Kits vorschauen kann (/previewkit)",
                "  kit-edit             - wo man Kit-Layouts bearbeiten kann",
                "  custom-kit           - wo man Custom-Kits erstellen/bearbeiten darf");
        c("spectator",
                "Zuschauer eines Matches (via /spectate oder Auto-Spectate)",
                "koennen NICHT durch Bloecke (auch Barrier) fliegen, wenn true.",
                "Echte SPECTATOR-Gamemode-Spieler sind nie betroffen.");
        c("anti-glitch",
                "Verhindert Durch-Glitchen (z.B. Ender-Pearls durch Waende).",
                "  enabled: an/aus",
                "  scope:   DUELS = nur in Matches | GLOBAL = ueberall",
                "  mode:    BLACKLIST = nur 'blocks' blocken",
                "           WHITELIST = nur durch 'blocks' erlaubt, Rest blockt",
                "           ALL = alle Bloecke blocken | NONE = aus",
                "  blocks:  Material-Liste fuer BLACKLIST/WHITELIST");
        c("leaderboard",
                "Ranglisten-Placeholder (%duels_<kategorie>_<platz>%).",
                "  format: Zeilen-Format. Platzhalter {rank} {name} {value} {category}",
                "  empty:  Text wenn kein Spieler auf dem Platz existiert",
                "  names:  Anzeigename je Kategorie (kills/deaths/wins/losses/",
                "          coins/kd/winrate) fuer {category}");
        c("chat",
                "Chat-Filter.",
                "  duel-isolated:    Spieler im Match chatten nur mit dem Match (an)",
                "  lobby-isolated:   eigener Chat nur fuer die Lobby-Welt(en)",
                "  per-world-worlds: Welten mit eigenem (isoliertem) Chat");
        c("tablist",
                "Tablist-Filter (default AUS).",
                "  duel-filter:      im Match nur Gegner/Mitspieler im TAB",
                "  per-world-worlds: in diesen Welten nur Spieler derselben Welt");
        c("currency",
                "Waehrungsname: was statt 'Coins' ueberall angezeigt wird",
                "(Scoreboard, Vergleich, Nachrichten via {currency}, Placeholder,",
                "Leaderboard). Beliebiger Text, z.B. 'Elo'.");
        c("permissions",
                "Auto-Deaktivierung bei fehlender Permission:",
                "  auto-disable-fly:       kein 'duels.fly' -> Fly automatisch aus",
                "  auto-disable-armortrim: kein Recht -> Armortrims werden entfernt");
        c("duel",
                "Duel-Anzeige.",
                "  show-map-in-request: Map/Arena in der Einladung + beim Start zeigen?");
        c("custom-kits",
                "Custom-Kit-System (von Spielern selbst erstellte Kits).",
                "  enabled:     System an/aus",
                "  show-button: 'Custom Kits'-Button fuer ALLE sichtbar (auch ohne",
                "               Permission)? false = nur mit Permission.",
                "  block-rules.precedence: fuer NORMALE Kits, Arena- vs Kit-Liste:",
                "      MERGE = beide Listen | ARENA = Arena ueberschreibt | KIT = Kit",
                "  tiers: Permission -> max. Anzahl Kits (hoechste zaehlt).",
                "      tier0=1, tier1=2, tier2=4 (Permissions frei waehlbar).");
        c("kits",
                "Kit-Auswahl-GUI: feste Slots pro Kit.",
                "  use-custom-slots: false = automatische, zentrierte Anordnung",
                "                    (Standard, wie bisher).",
                "  use-custom-slots: true  = Kits mit 'gui-slot' (in kits.yml, 0-53)",
                "                    erscheinen genau dort; der Rest fuellt frei auf.");

        mainConfig.set("config-comments-version", CURRENT);
        mainConfig.setComments("config-comments-version", java.util.Arrays.asList(
                "Interner Marker, damit die Kommentare nur einmal geschrieben werden.",
                "Nicht aendern."));
        return true;
    }

    /** Setzt einen Kommentar-Block ueber den angegebenen config-Key. */
    private void c(String path, String... lines) {
        mainConfig.setComments(path, java.util.Arrays.asList(lines));
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