package dev.duels.managers;

import dev.duels.DuelsPlugin;
import dev.duels.objects.DuelSession;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ScoreboardManager {

    private final DuelsPlugin plugin;
    private final Map<UUID, Scoreboard> playerScoreboards = new HashMap<>();
    private final Map<UUID, Objective> playerObjectives = new HashMap<>();

    public ScoreboardManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateScoreboard(Player player) {
        UUID uuid = player.getUniqueId();
        boolean inDuel = plugin.getDuelManager().isInDuel(uuid);

        Scoreboard board = playerScoreboards.get(uuid);
        Objective obj = playerObjectives.get(uuid);

        String title = plugin.getConfigManager().getMainConfig().getString("scoreboard-title", "§3§l🪓 Duels");

        if (board == null || obj == null) {
            org.bukkit.scoreboard.ScoreboardManager bukkitManager = Bukkit.getScoreboardManager();
            if (bukkitManager == null) return;

            board = bukkitManager.getNewScoreboard();

            if (obj != null) {
                obj.unregister();
            }

            obj = board.registerNewObjective("duels", "dummy", title);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            playerScoreboards.put(uuid, board);
            playerObjectives.put(uuid, obj);
            player.setScoreboard(board);
        } else {
            if (!obj.getDisplayName().equals(title)) {
                obj.setDisplayName(title);
            }
        }

        // Nametag-Sync: registriere Status-Teams auf diesem Observer-Board
        // und ordne JEDEN Online-Spieler dem passenden Team zu. Damit
        // erscheint der ⚔/👁-Prefix (Solo-Duel/Spectate) und [T1]/[T2]
        // (Team-Match) überm Spielerkopf — die Nametag-Sichtbarkeit hängt
        // vom Scoreboard des BEOBACHTERS ab, nicht vom Main-Scoreboard.
        syncNametagTeams(board);

        // FFA/Team-Teilnehmer bekommen eigene Scoreboard-Lines
        PartyFFAManager.FFASession ffaSess = plugin.getPartyFFAManager() != null
                ? plugin.getPartyFFAManager().getSession(uuid) : null;
        boolean inFFA = ffaSess != null;

        // Linien holen: FFA, Team, Duel oder Lobby
        java.util.List<String> lines;
        if (inFFA && ffaSess.isTeamMode()) {
            lines = plugin.getConfigManager().getMainConfig().getStringList("team-scoreboard-lines");
            if (lines == null || lines.isEmpty()) {
                lines = plugin.getConfigManager().getMainConfig().getStringList("duel-scoreboard-lines");
            }
        } else if (inFFA) {
            lines = plugin.getConfigManager().getMainConfig().getStringList("ffa-scoreboard-lines");
            if (lines == null || lines.isEmpty()) {
                lines = plugin.getConfigManager().getMainConfig().getStringList("duel-scoreboard-lines");
            }
        } else if (inDuel) {
            lines = plugin.getConfigManager().getMainConfig().getStringList("duel-scoreboard-lines");
        } else {
            lines = plugin.getConfigManager().getMainConfig().getStringList("scoreboard-lines");
        }

        // Alte Einträge löschen
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        // Platzhalter ersetzen
        int score = lines.size();
        for (String line : lines) {
            line = replacePlaceholders(player, line);

            if (line.isEmpty() || line.equals(" ")) {
                String emptyLineId = getEmptyLineId(score);
                obj.getScore(emptyLineId).setScore(score);
            } else {
                obj.getScore(line).setScore(score);
            }
            score--;
        }
    }

    private String replacePlaceholders(Player player, String line) {
        UUID uuid = player.getUniqueId();

        // Spieler Stats
        int kills = plugin.getPlayerManager().getStat(uuid, "kills");
        int deaths = plugin.getPlayerManager().getStat(uuid, "deaths");
        int wins = plugin.getPlayerManager().getStat(uuid, "wins");
        int losses = plugin.getPlayerManager().getStat(uuid, "losses");
        int coins = plugin.getPlayerManager().getStat(uuid, "coins");
        double kd = deaths == 0 ? kills : (double) kills / deaths;
        String winrate = plugin.getPlayerManager().calculateWinrate(wins, losses);

        // Allgemeine Stats
        int online = Bukkit.getOnlinePlayers().size();
        int playing = plugin.getDuelManager().getActiveDuelCount() * 2;

        // Duel-spezifische Stats
        DuelSession session = plugin.getDuelManager().getDuelSession(uuid);
        String opponentName = "None";
        String mapName = plugin.getConfigManager().getMainConfig().getString("default-map", "§cDefault");
        int playerPing = player.getPing();
        int opponentPing = 0;
        int timeLeft = 0;
        int yourWins = 0;
        int oppWins = 0;
        int round = 0;
        int bestOf = 0;
        int requiredWins = 0;
        String scoreStr = "0-0";

        if (session != null) {
            opponentName = session.getOpponentName(uuid);
            mapName = session.getArenaName();
            timeLeft = session.getTimeLeft();
            round = session.getRound();
            bestOf = session.getBestOf();
            requiredWins = session.requiredWins();
            if (session.getPlayer1().equals(uuid)) {
                yourWins = session.getWinsP1();
                oppWins = session.getWinsP2();
            } else {
                yourWins = session.getWinsP2();
                oppWins = session.getWinsP1();
            }
            scoreStr = yourWins + " - " + oppWins;

            UUID opponentId = session.getOpponent(uuid);
            if (opponentId != null) {
                Player opponent = Bukkit.getPlayer(opponentId);
                if (opponent != null) {
                    opponentPing = opponent.getPing();
                }
            }
        }

        // %timeleft%: -1 = until-death (kein Timer) → Unendlich-Zeichen.
        String timeLeftStr = (timeLeft < 0) ? "§5§l∞" : (timeLeft + "s");

        // FFA/Team-Placeholders: %alive%, %yourteam%, %enemyteam%
        int aliveCount = 0;
        int yourTeamAlive = 0;
        int enemyTeamAlive = 0;
        String ffaMapName = mapName;
        PartyFFAManager.FFASession ffaSession = plugin.getPartyFFAManager() != null
                ? plugin.getPartyFFAManager().getSession(uuid) : null;
        if (ffaSession != null) {
            aliveCount = ffaSession.alive.size();
            if (ffaSession.reservedArena != null) {
                ffaMapName = ffaSession.reservedArena.getName();
            }
            if (ffaSession.isTeamMode()) {
                int myTeam = ffaSession.getTeam(uuid);
                for (java.util.UUID u : ffaSession.alive) {
                    int t = ffaSession.getTeam(u);
                    if (t == myTeam) yourTeamAlive++;
                    else enemyTeamAlive++;
                }
            }
            // Auch Spectator (tote FFA-Spieler) sehen die richtigen Alive-Zahlen
            if (!ffaSession.alive.contains(uuid) && ffaSession.allParticipants.contains(uuid)) {
                // Spieler ist tot, aber noch als Spectator im Match
                aliveCount = ffaSession.alive.size();
            }
        }
        // FFA/Team: %map% überschreiben mit Arena-Name
        if (ffaSession != null && !ffaMapName.equals(mapName)) {
            mapName = ffaMapName;
        }

        // Alle Platzhalter ersetzen
        return line
                .replace("%kills%", String.valueOf(kills))
                .replace("%deaths%", String.valueOf(deaths))
                .replace("%wins%", String.valueOf(wins))
                .replace("%losses%", String.valueOf(losses))
                .replace("%coins%", String.valueOf(coins))
                .replace("%kd%", String.format("%.2f", kd))
                .replace("%winrate%", winrate)
                .replace("%online%", String.valueOf(online))
                .replace("%playing%", String.valueOf(playing))
                .replace("%opponent%", opponentName)
                .replace("%map%", mapName)
                .replace("%playerping%", playerPing + "ms")
                .replace("%opponentping%", opponentPing + "ms")
                .replace("%timeleft%", timeLeftStr)
                .replace("%round%", String.valueOf(round))
                .replace("%bestof%", String.valueOf(bestOf))
                .replace("%requiredwins%", String.valueOf(requiredWins))
                .replace("%score%", scoreStr)
                .replace("%yourwins%", String.valueOf(yourWins))
                .replace("%opponentwins%", String.valueOf(oppWins))
                .replace("%alive%", String.valueOf(aliveCount))
                .replace("%yourteam%", String.valueOf(yourTeamAlive))
                .replace("%enemyteam%", String.valueOf(enemyTeamAlive))
                .replace("%currency%", plugin.getConfigManager().getCurrencyName());
    }

    private String getEmptyLineId(int index) {
        return ChatColor.values()[index % ChatColor.values().length].toString() + ChatColor.RESET;
    }

    /** Status-Team-Namen für Nametag-Prefix (Observer-Scoreboard). */
    private static final String NT_DUEL = "duels_duel";
    private static final String NT_SPEC = "duels_spec";
    private static final String NT_T1   = "duels_t1";
    private static final String NT_T2   = "duels_t2";
    private static final String[] NT_TEAMS = { NT_DUEL, NT_SPEC, NT_T1, NT_T2 };

    private void syncNametagTeams(Scoreboard board) {
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
        String duelSym = cfg.getString("status.duel-symbol", "&c ⚔").replace("&", "§").trim() + " ";
        String specSym = cfg.getString("status.spec-symbol", "&7 👁").replace("&", "§").trim() + " ";
        String t1Sym   = cfg.getString("status.team1-symbol", "&b[T1] ").replace("&", "§");
        String t2Sym   = cfg.getString("status.team2-symbol", "&c[T2] ").replace("&", "§");
        ensureNametagTeam(board, NT_DUEL, ChatColor.RED,  duelSym);
        ensureNametagTeam(board, NT_SPEC, ChatColor.GRAY, specSym);
        ensureNametagTeam(board, NT_T1,   ChatColor.AQUA, t1Sym);
        ensureNametagTeam(board, NT_T2,   ChatColor.RED,  t2Sym);
        for (Player p : Bukkit.getOnlinePlayers()) {
            String target = computeStatusTeam(p);
            applyEntry(board, p.getName(), target);
        }
        // Auch auf das Main-Scoreboard pushen — TAB-Plugins (NEZNAMY-TAB
        // etc.) ersetzen die Scoreboard-Teams pro Spieler, aber respektieren
        // typischerweise das Main-Scoreboard. Damit erscheint [T1]/[T2]
        // überm Kopf auch wenn TAB unsere per-Player-Boards überschreibt.
        try {
            Scoreboard main = Bukkit.getScoreboardManager() == null
                    ? null : Bukkit.getScoreboardManager().getMainScoreboard();
            if (main != null && main != board) {
                ensureNametagTeam(main, NT_T1, ChatColor.AQUA, t1Sym);
                ensureNametagTeam(main, NT_T2, ChatColor.RED,  t2Sym);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String target = computeStatusTeam(p);
                    // Auf Main nur T1/T2 (DUEL/SPEC sollen NICHT überm Kopf).
                    String mainTarget = (target != null
                            && (target.equals(NT_T1) || target.equals(NT_T2)))
                            ? target : null;
                    applyEntry(main, p.getName(), mainTarget);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void ensureNametagTeam(Scoreboard board, String name, ChatColor color, String prefix) {
        Team t = board.getTeam(name);
        if (t == null) {
            try { t = board.registerNewTeam(name); }
            catch (IllegalArgumentException ignored) { t = board.getTeam(name); }
        }
        if (t == null) return;
        try { t.setColor(color); } catch (Throwable ignored) {}
        // Prefix sowohl per Adventure-API (Paper 1.21+) als auch per Legacy-
        // setPrefix (Spigot kompatibel) setzen. Manche TAB/Plugins
        // respektieren nur eine der beiden Methoden.
        try {
            t.prefix(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().deserialize(prefix));
        } catch (Throwable ignored) {}
        try { t.setPrefix(prefix); } catch (Throwable ignored) {}
        try {
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
            t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            t.setAllowFriendlyFire(true);
        } catch (Throwable ignored) {}
    }

    private String computeStatusTeam(Player p) {
        UUID uuid = p.getUniqueId();
        if (plugin.getPartyFFAManager() != null) {
            dev.duels.managers.PartyFFAManager.FFASession s =
                    plugin.getPartyFFAManager().getSession(uuid);
            if (s != null && s.isTeamMode()) {
                int t = s.getTeam(uuid);
                if (t == 1) return NT_T1;
                if (t == 2) return NT_T2;
            }
        }
        // ⚔/👁 sind NUR via TAB-Plugin + %duels_status% gewünscht — NICHT
        // über dem Kopf. Daher keine Zuordnung zu NT_DUEL/NT_SPEC mehr
        // (sonst würde der Prefix auch im Nametag erscheinen). Nur T1/T2
        // (Team-Match Color-Coding) bleibt sichtbar überm Kopf.
        return null;
    }

    private void applyEntry(Scoreboard board, String name, String targetTeam) {
        for (String key : NT_TEAMS) {
            Team t = board.getTeam(key);
            if (t == null) continue;
            if (t.hasEntry(name)) {
                if (key.equals(targetTeam)) return;
                try { t.removeEntry(name); } catch (Throwable ignored) {}
            }
        }
        if (targetTeam != null) {
            Team t = board.getTeam(targetTeam);
            if (t != null) {
                try { t.addEntry(name); } catch (Throwable ignored) {}
            }
        }
    }

    public void removeScoreboard(UUID uuid) {
        Objective obj = playerObjectives.remove(uuid);
        if (obj != null) {
            obj.unregister();
        }
        playerScoreboards.remove(uuid);
    }

    public void updateAllScoreboards() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateScoreboard(player);
        }
    }
}