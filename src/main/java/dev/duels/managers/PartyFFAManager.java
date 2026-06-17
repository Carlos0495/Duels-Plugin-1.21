package dev.duels.managers;

import dev.duels.DuelsPlugin;
import dev.duels.objects.Party;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Verwaltet "Party-FFA"-Sessions: alle Mitglieder einer Party werden an
 * den konfigurierten Party-FFA-Spawn teleportiert, bekommen das gewählte
 * Kit und kämpfen all-vs-all (1 Leben pro Spieler) auf einer Map.
 *
 * <ul>
 *   <li>Grace-Period (Default 10s) – PvP geblockt, Countdown-Broadcast.</li>
 *   <li>Tod = automatischer Spectator-Modus (verbleibt im Match-Channel).</li>
 *   <li>Letzter Überlebender = Sieger, alle Spectator gehen zurück in Lobby.</li>
 * </ul>
 *
 * <p>Bewusst getrennt vom {@link DuelManager}, weil FFA keine 1v1-Logik hat
 * (Rounds, Best-of, Wins) und sonst die Duell-Pipeline verwirrt.</p>
 */
public class PartyFFAManager {

    private final DuelsPlugin plugin;

    /** playerId -> Session, an der der Spieler gerade teilnimmt. */
    private final Map<UUID, FFASession> playerToSession = new HashMap<>();
    /** Aktive Sessions (key = leaderId zum Zeitpunkt des Starts). */
    private final Map<UUID, FFASession> sessionsByLeader = new HashMap<>();
    /**
     * Spieler die während eines FFA-/Team-Countdowns frozen sind (3s
     * Duel-Style). PlayerListener#onMove cancelt PlayerMove für diese UUIDs.
     */
    private final Set<UUID> frozenFFAPlayers = new HashSet<>();

    public boolean isFrozen(UUID uuid) {
        return frozenFFAPlayers.contains(uuid);
    }

    public PartyFFAManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Scoreboard-Team-Namen (Bukkit Main-Scoreboard) für Team-Coloring. */
    public static final String SB_TEAM_1 = "duels_t1";
    public static final String SB_TEAM_2 = "duels_t2";

    /**
     * Erstellt (oder updated) die Bukkit-Scoreboard-Teams, die für die
     * Team-Match-Color verwendet werden. Diese Teams haben Color-Prefix +
     * keine Kollision mit Teammates und beeinflussen den Tab-Namen in
     * vielen Tab-Plugin-Setups.
     */
    private void ensureScoreboardTeams() {
        org.bukkit.scoreboard.Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        ensureTeam(main, SB_TEAM_1, org.bukkit.ChatColor.AQUA, "§b[T1] ");
        ensureTeam(main, SB_TEAM_2, org.bukkit.ChatColor.RED,  "§c[T2] ");
    }

    private void ensureTeam(org.bukkit.scoreboard.Scoreboard sb, String name,
                            org.bukkit.ChatColor color, String prefix) {
        org.bukkit.scoreboard.Team t = sb.getTeam(name);
        if (t == null) {
            try { t = sb.registerNewTeam(name); }
            catch (IllegalArgumentException ignored) { t = sb.getTeam(name); }
        }
        if (t == null) return;
        try {
            t.setColor(color);
            // §-Codes via LegacyComponentSerializer parsen, sonst landet
            // der Raw-String "§b[T1] " im Tab und wird nicht farbig gerendert.
            t.prefix(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().deserialize(prefix));
            t.setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE,
                    org.bukkit.scoreboard.Team.OptionStatus.NEVER);
            t.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY,
                    org.bukkit.scoreboard.Team.OptionStatus.ALWAYS);
            t.setAllowFriendlyFire(false);
        } catch (Throwable ignored) {}
    }

    /** Entfernt alle Session-Member aus den Scoreboard-Teams (Match-Ende). */
    private void clearScoreboardTeamsFor(FFASession session) {
        try {
            org.bukkit.scoreboard.Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            org.bukkit.scoreboard.Team t1Team = main.getTeam(SB_TEAM_1);
            org.bukkit.scoreboard.Team t2Team = main.getTeam(SB_TEAM_2);
            for (UUID u : session.allParticipants) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                if (t1Team != null) t1Team.removeEntry(p.getName());
                if (t2Team != null) t2Team.removeEntry(p.getName());
            }
        } catch (Throwable ignored) {}
    }

    public boolean isParticipant(UUID uuid) {
        return playerToSession.containsKey(uuid);
    }

    public FFASession getSession(UUID uuid) {
        return playerToSession.get(uuid);
    }

    /** Liefert die Arena des FFA/Team-Matches des Spielers (oder null). */
    public dev.duels.objects.Arena getArenaOf(UUID uuid) {
        FFASession s = playerToSession.get(uuid);
        return s != null ? s.reservedArena : null;
    }

    public java.util.Collection<FFASession> getAllSessions() {
        return sessionsByLeader.values();
    }

    /**
     * Startet eine Party-FFA-Session. Liefert eine Fehlermeldung wenn etwas
     * schiefläuft, sonst {@code null} bei Erfolg.
     */
    public String start(Party party, String kitName, List<Player> members) {
        if (party == null) return "No party.";
        if (kitName == null || kitName.isEmpty()) return "Invalid kit.";
        if (members == null || members.size() < 2) return "Need at least 2 online members.";

        if (!plugin.getKitManager().kitExists(kitName)) {
            return "Kit no longer exists.";
        }

        // Pro Party eine eigene Arena reservieren (Multi-Map FFA, User-Wunsch:
        // mehrere Parties können parallel auf verschiedenen Maps FFA spielen).
        // Fallback: wenn keine Arena einen eigenen FFA-Spawn hat, nutzen wir
        // den globalen partyFFASpawn (Legacy-Pfad).
        dev.duels.objects.Arena reservedArena =
                plugin.getArenaManager().getRandomFFAArenaForKit(kitName);
        Location spawn;
        if (reservedArena != null) {
            spawn = reservedArena.getFfaSpawn();
            reservedArena.setInUse(true);
        } else {
            spawn = plugin.getArenaManager().getPartyFFASpawn();
            if (spawn == null) {
                return "No FFA arena available for this kit. §7Admin: stand on the FFA spawn point and run §e/arena setffaspawn <arena> §7(per arena), or §e/duels setpartyffaspawn §7for the legacy global spawn.";
            }
        }

        for (UUID m : party.getMembers()) {
            if (playerToSession.containsKey(m)) {
                if (reservedArena != null) reservedArena.setInUse(false);
                return "Party already has an active FFA session.";
            }
        }

        int graceSeconds = plugin.getConfigManager().getMainConfig()
                .getInt("party.ffa-grace-seconds", 10);

        FFASession session = new FFASession(party.getLeader(), kitName);
        session.reservedArena = reservedArena;
        session.graceTicksLeft = Math.max(0, graceSeconds) * 20;
        for (Player p : members) {
            if (p == null || !p.isOnline()) continue;
            if (plugin.getDuelManager().isInDuel(p.getUniqueId())) continue;
            session.alive.add(p.getUniqueId());
            session.allParticipants.add(p.getUniqueId());
            playerToSession.put(p.getUniqueId(), session);
        }
        if (session.alive.size() < 2) {
            for (UUID u : session.alive) playerToSession.remove(u);
            return "Need at least 2 online members not currently in a duel.";
        }
        sessionsByLeader.put(session.leaderId, session);

        // Teleport + Kit + Grace
        for (UUID u : session.alive) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            if (plugin.getSpectateManager() != null) plugin.getSpectateManager().clearSpectating(p);
            if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL) p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            plugin.getPlayerManager().safeTeleport(p, spawn);
            DuelManager.clearFullInventory(p);
            plugin.getKitManager().giveKit(p, kitName);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            p.setSaturation(20f);
            plugin.getKitManager().applyKitStartEffects(p, kitName);
            if (graceSeconds > 0) {
                p.sendMessage(plugin.getConfigManager().prefixed("ffa.started-grace",
                        "&6Party FFA &7started! &ePvP enabled in {seconds}s&7. &fLast one alive wins.",
                        java.util.Map.of("seconds", String.valueOf(graceSeconds))));
            } else {
                p.sendMessage(plugin.getConfigManager().prefixed("ffa.started", "&6Party FFA &7started! &fLast one alive wins."));
            }
        }

        // Tablist-Filter (falls aktiv) für alle neu berechnen.
        plugin.getPlayerManager().refreshAllVisibility();

        // Grace-Countdown-Task: zeigt 10/5/4/3/2/1 → GO! Im Anschluss
        // wird graceTicksLeft auf 0 gesetzt; Damage-Pfad checkt das.
        if (graceSeconds > 0) {
            session.graceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (session.graceTicksLeft <= 0) {
                    if (session.graceTask != null) {
                        session.graceTask.cancel();
                        session.graceTask = null;
                    }
                    broadcastToSession(session, plugin.getConfigManager().getMessage("ffa.go-pvp-enabled", "&a&lGO! &7PvP is now enabled."));
                    showTitleToSession(session, "§a§lGO!", "§7PvP enabled", 0, 30, 10);
                    return;
                }
                int secLeft = (session.graceTicksLeft + 19) / 20;
                if (session.graceTicksLeft % 20 == 0) {
                    // Title-Countdown jede Sekunde (User-Wunsch).
                    showTitleToSession(session, "§e§l" + secLeft, "§7PvP in §6" + secLeft + "s", 0, 25, 5);
                    if (secLeft <= 5 || secLeft == 10) {
                        broadcastToSession(session, plugin.getConfigManager().getMessage("ffa.pvp-countdown", "&ePvP in &6{seconds}s&7...", java.util.Map.of("seconds", String.valueOf(secLeft))));
                    }
                }
                session.graceTicksLeft -= 20;
            }, 0L, 20L);
        }
        return null;
    }

    /**
     * Startet ein Team-vs-Team-Match auf einer Arena. team1 und team2 sind
     * Listen von Online-Spielern. Verhält sich wie FFA (Spectator nach Tod,
     * letztes überlebendes Team gewinnt), aber Teammates können sich nicht
     * gegenseitig angreifen (Friendly Fire blockiert).
     *
     * @return Fehlermeldung oder {@code null} bei Erfolg.
     */
    public String startTeams(Party party, String kitName, List<Player> team1, List<Player> team2) {
        if (party == null) return "No party.";
        if (kitName == null || kitName.isEmpty()) return "Invalid kit.";
        if (team1 == null || team2 == null || team1.isEmpty() || team2.isEmpty()) {
            return "Both teams need at least one player.";
        }
        if (!plugin.getKitManager().kitExists(kitName)) return "Kit no longer exists.";

        // Team-Match nutzt EINE reguläre Duel-Arena (spawn1 + spawn2) statt
        // FFA-Spawn — User-Wunsch: Team1 spawnt bei spawn1, Team2 bei spawn2,
        // wie bei einem 1v1. Verhält sich beim Reset/Cleanup wie ein Duell.
        dev.duels.objects.Arena reservedArena =
                plugin.getArenaManager().getRandomAvailableArenaForKit(kitName);
        if (reservedArena == null) {
            return "No arena available for this kit.";
        }
        Location spawn1 = reservedArena.getSpawn1();
        Location spawn2 = reservedArena.getSpawn2();
        if (spawn1 == null || spawn2 == null) {
            return "Arena '" + reservedArena.getName() + "' has no spawn1/spawn2 set.";
        }
        reservedArena.setInUse(true);

        // Check niemand schon im FFA/Duel
        for (Player p : team1) {
            if (p == null) continue;
            if (playerToSession.containsKey(p.getUniqueId())
                    || plugin.getDuelManager().isInDuel(p.getUniqueId())) {
                reservedArena.setInUse(false);
                return p.getName() + " is already in a duel/FFA.";
            }
        }
        for (Player p : team2) {
            if (p == null) continue;
            if (playerToSession.containsKey(p.getUniqueId())
                    || plugin.getDuelManager().isInDuel(p.getUniqueId())) {
                reservedArena.setInUse(false);
                return p.getName() + " is already in a duel/FFA.";
            }
        }

        int graceSeconds = plugin.getConfigManager().getMainConfig()
                .getInt("party.ffa-grace-seconds", 10);

        FFASession session = new FFASession(party.getLeader(), kitName);
        session.reservedArena = reservedArena;
        session.graceTicksLeft = Math.max(0, graceSeconds) * 20;
        for (Player p : team1) {
            if (p == null || !p.isOnline()) continue;
            session.alive.add(p.getUniqueId());
            session.allParticipants.add(p.getUniqueId());
            session.teams.put(p.getUniqueId(), 1);
            playerToSession.put(p.getUniqueId(), session);
        }
        for (Player p : team2) {
            if (p == null || !p.isOnline()) continue;
            session.alive.add(p.getUniqueId());
            session.allParticipants.add(p.getUniqueId());
            session.teams.put(p.getUniqueId(), 2);
            playerToSession.put(p.getUniqueId(), session);
        }
        sessionsByLeader.put(session.leaderId, session);

        // Scoreboard-Teams für farbige Namen + Friendly-Fire-Anzeige.
        // Diese werden im Bukkit-Main-Scoreboard registriert, sodass
        // (a) Teammates ihre Namen in §b/§c sehen,
        // (b) viele Tab-Plugins den Color-Prefix respektieren,
        // (c) collide=NEVER → Spieler können nicht durch Teammates pushen.
        ensureScoreboardTeams();
        org.bukkit.scoreboard.Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team t1Team = main.getTeam(SB_TEAM_1);
        org.bukkit.scoreboard.Team t2Team = main.getTeam(SB_TEAM_2);

        // Listen für Team-Member-Anzeige im Chat
        StringBuilder t1Names = new StringBuilder();
        StringBuilder t2Names = new StringBuilder();
        for (UUID u : session.alive) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            int t = session.getTeam(u);
            if (t == 1) {
                if (t1Names.length() > 0) t1Names.append("§7, ");
                t1Names.append("§b").append(p.getName());
                if (t1Team != null) t1Team.addEntry(p.getName());
            } else if (t == 2) {
                if (t2Names.length() > 0) t2Names.append("§7, ");
                t2Names.append("§c").append(p.getName());
                if (t2Team != null) t2Team.addEntry(p.getName());
            }
        }

        // Teleport: Team1 → spawn1, Team2 → spawn2 (wie 1v1)
        for (UUID u : session.alive) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            int t = session.getTeam(u);
            Location targetSpawn = (t == 1) ? spawn1 : spawn2;
            if (plugin.getSpectateManager() != null) plugin.getSpectateManager().clearSpectating(p);
            if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL) p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            plugin.getPlayerManager().safeTeleport(p, targetSpawn);
            DuelManager.clearFullInventory(p);
            plugin.getKitManager().giveKit(p, kitName);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            p.setSaturation(20f);
            plugin.getKitManager().applyKitStartEffects(p, kitName);
            // Team-Label überm Kopf via TextDisplay (umgeht TAB-Plugin-
            // Override-Probleme mit Scoreboard-Teams). 2 Ticks Delay,
            // damit der Teleport im neuen World/Pos sauber durch ist.
            final int teamNum = t;
            final UUID pid = u;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player pl = Bukkit.getPlayer(pid);
                if (pl != null && pl.isOnline()) {
                    plugin.getTeamLabelManager().spawnLabel(pl, teamNum);
                }
            }, 2L);
            String teamColor = t == 1 ? "§b" : "§c";
            String teamName = t == 1 ? "Team 1" : "Team 2";
            p.sendMessage(plugin.getConfigManager().prefixed("team.match-header", "{color}&l{team} &7on {arena}", java.util.Map.of("color", teamColor, "team", teamName, "arena", reservedArena.getName())));
            p.sendMessage(plugin.getConfigManager().prefixed("team.team1-list", "&bTeam 1: &7{players}", java.util.Map.of("players", t1Names.toString())));
            p.sendMessage(plugin.getConfigManager().prefixed("team.team2-list", "&cTeam 2: &7{players}", java.util.Map.of("players", t2Names.toString())));
            // Blindness während Countdown (wie Duel)
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.BLINDNESS, 60, 1, false, false));
            // Freeze für die Countdown-Dauer
            frozenFFAPlayers.add(u);
        }

        // Tablist-Filter (falls aktiv) für alle neu berechnen.
        plugin.getPlayerManager().refreshAllVisibility();

        // 3-Sekunden Duel-Style Countdown (statt 10s FFA-Grace).
        // Spieler sind frozen via frozenFFAPlayers — PlayerListener#onMove
        // ruft isFrozen() ab und cancelt die Bewegung.
        session.graceTicksLeft = 3 * 20;
        session.graceTask = new org.bukkit.scheduler.BukkitRunnable() {
            int time = 3;
            @Override
            public void run() {
                if (time > 0) {
                    String cdt = plugin.getConfigManager().getMessage(
                            "duel.countdown-title", "§c" + time,
                            java.util.Map.of("seconds", String.valueOf(time)));
                    showTitleToSession(session, cdt, "§7Get ready", 0, 20, 0);
                    for (UUID u : session.allParticipants) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) {
                            float pitch = time == 3 ? 0.5f : (time == 2 ? 0.8f : 1.2f);
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1f, pitch);
                        }
                    }
                    time--;
                } else {
                    showTitleToSession(session,
                            plugin.getConfigManager().getMessage("duel.fight-title", "§a§lFIGHT!"),
                            "§7Team vs Team", 0, 20, 10);
                    for (UUID u : session.allParticipants) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) {
                            p.playSound(p.getLocation(),
                                    org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);
                            frozenFFAPlayers.remove(u);
                        }
                    }
                    session.graceTicksLeft = 0;
                    cancel();
                    session.graceTask = null;
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
        return null;
    }

    /** True wenn die Session noch in der Grace-Period ist (kein PvP). */
    public boolean isInGrace(UUID uuid) {
        FFASession s = playerToSession.get(uuid);
        return s != null && s.graceTicksLeft > 0;
    }

    /**
     * Wird aus {@code DuelListener#onPlayerDeath} aufgerufen wenn ein
     * FFA-Teilnehmer stirbt. Spieler geht NICHT zurück in die Lobby —
     * stattdessen Spectator-Modus, schaut den Rest des Matches zu.
     */
    public void handleDeath(Player dead) {
        if (dead == null) return;
        FFASession session = playerToSession.get(dead.getUniqueId());
        if (session == null) return;
        session.alive.remove(dead.getUniqueId());

        // Team-Label entfernen (Spieler ist tot, kein Label mehr nötig).
        plugin.getTeamLabelManager().removeLabel(dead.getUniqueId());

        broadcastToSession(session,
                "§c" + dead.getName() + " §7was eliminated. §f" + session.alive.size() + " §7alive.");

        // End-Check ZUERST: wenn der Tod die Session beendet, KEIN
        // Auto-Spectate scheduln — sonst race-condition: endSession
        // teleportiert in die Lobby (SURVIVAL), und 3 Ticks später
        // würde der scheduled Auto-Spectate-Task den Spieler zurück
        // in die Arena (SPECTATOR) ziehen.
        boolean willEnd;
        if (session.isTeamMode()) {
            int aliveT1 = 0, aliveT2 = 0;
            for (UUID u : session.alive) {
                int t = session.getTeam(u);
                if (t == 1) aliveT1++;
                else if (t == 2) aliveT2++;
            }
            willEnd = (aliveT1 == 0 || aliveT2 == 0);
        } else {
            willEnd = session.alive.size() <= 1;
        }

        if (!willEnd) {
            UUID anchor = null;
            if (session.isTeamMode()) {
                int myTeam = session.getTeam(dead.getUniqueId());
                for (UUID u : session.alive) {
                    if (session.getTeam(u) == myTeam) { anchor = u; break; }
                }
            }
            if (anchor == null && !session.alive.isEmpty()) {
                anchor = session.alive.iterator().next();
            }
            final UUID anchorFinal = anchor;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!dead.isOnline()) return;
                plugin.getSpectateManager().enterAutoSpectateForFFA(dead, anchorFinal, session.leaderId);
            }, 3L);
        } else {
            endSession(session);
        }
    }

    /**
     * Spieler verlässt freiwillig das Match via /spawn — wird wie ein Tod
     * behandelt (Spectator-Modus) aber ohne Kill/Death-Stat-Änderungen.
     */
    public void voluntaryLeave(Player player) {
        if (player == null) return;
        FFASession session = playerToSession.get(player.getUniqueId());
        if (session == null) return;
        session.alive.remove(player.getUniqueId());

        plugin.getTeamLabelManager().removeLabel(player.getUniqueId());

        broadcastToSession(session,
                "§e" + player.getName() + " §7left the match. §f" + session.alive.size() + " §7alive.");

        boolean willEnd;
        if (session.isTeamMode()) {
            int aliveT1 = 0, aliveT2 = 0;
            for (UUID u : session.alive) {
                int t = session.getTeam(u);
                if (t == 1) aliveT1++;
                else if (t == 2) aliveT2++;
            }
            willEnd = (aliveT1 == 0 || aliveT2 == 0);
        } else {
            willEnd = session.alive.size() <= 1;
        }

        if (!willEnd) {
            UUID anchor = null;
            if (session.isTeamMode()) {
                int myTeam = session.getTeam(player.getUniqueId());
                for (UUID u : session.alive) {
                    if (session.getTeam(u) == myTeam) { anchor = u; break; }
                }
            }
            if (anchor == null && !session.alive.isEmpty()) {
                anchor = session.alive.iterator().next();
            }
            final UUID anchorFinal = anchor;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                plugin.getSpectateManager().enterAutoSpectateForFFA(player, anchorFinal, session.leaderId);
            }, 1L);
        } else {
            endSession(session);
        }
    }

    public void handlePlayerQuit(UUID uuid) {
        FFASession session = playerToSession.remove(uuid);
        if (session == null) return;
        session.alive.remove(uuid);
        boolean shouldEnd;
        if (session.isTeamMode()) {
            int aliveT1 = 0, aliveT2 = 0;
            for (UUID u : session.alive) {
                int t = session.getTeam(u);
                if (t == 1) aliveT1++;
                else if (t == 2) aliveT2++;
            }
            shouldEnd = (aliveT1 == 0 || aliveT2 == 0);
        } else {
            shouldEnd = session.alive.size() <= 1;
        }
        if (shouldEnd) {
            endSession(session);
        }
    }

    /**
     * Erlaubt Teilnehmern derselben Session sich gegenseitig Schaden zu­
     * fügen, sofern die Grace-Period vorbei ist. Nicht-Teilnehmer-Treffer
     * werden separat im DuelListener blockiert.
     */
    public boolean canDamage(UUID attackerId, UUID targetId) {
        FFASession a = playerToSession.get(attackerId);
        FFASession b = playerToSession.get(targetId);
        if (a == null || a != b) return false;
        if (a.graceTicksLeft > 0) return false;
        // Team-Modus: Teammates können sich nicht gegenseitig angreifen
        // (Friendly Fire blockiert, User-Wunsch).
        if (a.isTeamMode()) {
            int ta = a.getTeam(attackerId);
            int tb = a.getTeam(targetId);
            if (ta > 0 && ta == tb) return false;
        }
        return true;
    }

    private void endSession(FFASession session) {
        if (session.ended) return;
        session.ended = true;
        if (session.graceTask != null) {
            try { session.graceTask.cancel(); } catch (Throwable ignored) {}
            session.graceTask = null;
        }

        int coinReward = plugin.getConfigManager().getMainConfig().getInt("coins.win-reward", 10);

        if (session.isTeamMode()) {
            // Team-Sieger ermitteln (das Team mit noch lebenden Spielern)
            int winningTeam = 0;
            for (UUID u : session.alive) {
                int t = session.getTeam(u);
                if (t > 0) { winningTeam = t; break; }
            }
            // Alle Mitglieder des Gewinnerteams (auch tote zählen für Stats —
            // sie waren im Team, das gewonnen hat, daher Win + Coins)
            String winningName = winningTeam == 1 ? "§bTeam 1" : "§cTeam 2";
            broadcastToSession(session, plugin.getConfigManager().getMessage("ffa.winner", "&6&lWinner: {player}", java.util.Map.of("player", winningName)));
            for (UUID u : session.allParticipants) {
                Player p = Bukkit.getPlayer(u);
                int t = session.getTeam(u);
                java.util.Map<String,String> phT = java.util.Map.of("team", winningName);
                if (t == winningTeam && t > 0) {
                    if (p != null && p.isOnline()) {
                        p.sendTitle(
                            plugin.getConfigManager().getMessage("team.win-title", "§a§lTEAM VICTORY", phT),
                            plugin.getConfigManager().getMessage("team.win-subtitle", "§7" + winningName + " §7wins!", phT),
                            0, 60, 20);
                    }
                    plugin.getPlayerManager().addStat(u, "wins", 1);
                    plugin.getPlayerManager().addStat(u, "coins", coinReward);
                    if (p != null && p.isOnline()) {
                        p.sendMessage(plugin.getConfigManager().prefixed("team.coin-reward", "&e+&6{coins} &e{currency} &7(Team win reward)", java.util.Map.of("coins", String.valueOf(coinReward))));
                    }
                } else if (t > 0) {
                    if (p != null && p.isOnline()) {
                        p.sendTitle(
                            plugin.getConfigManager().getMessage("team.lose-title", "§c§lDEFEAT", phT),
                            plugin.getConfigManager().getMessage("team.lose-subtitle", "§7" + winningName + " §7won", phT),
                            0, 60, 20);
                    }
                    plugin.getPlayerManager().addStat(u, "losses", 1);
                }
            }
        } else {
            Player winner = null;
            if (session.alive.size() == 1) {
                UUID winnerId = session.alive.iterator().next();
                winner = Bukkit.getPlayer(winnerId);
                playerToSession.remove(winnerId);
            }
            if (winner != null) {
                broadcastToSession(session, plugin.getConfigManager().getMessage("ffa.winner-named", "&6&lWinner: &e{player}", java.util.Map.of("player", winner.getName())));
                final Player win = winner;
                java.util.Map<String,String> phF = java.util.Map.of("winner", win.getName());
                win.sendTitle(
                    plugin.getConfigManager().getMessage("ffa.win-title", "§a§lFFA VICTORY", phF),
                    plugin.getConfigManager().getMessage("ffa.win-subtitle", "§7You won the FFA!", phF),
                    0, 60, 20);
                for (UUID u : session.allParticipants) {
                    if (u.equals(win.getUniqueId())) continue;
                    Player p = Bukkit.getPlayer(u);
                    if (p != null && p.isOnline()) {
                        p.sendTitle(
                            plugin.getConfigManager().getMessage("ffa.lose-title", "§c§lDEFEAT", phF),
                            plugin.getConfigManager().getMessage("ffa.lose-subtitle", "§7" + win.getName() + " §7won the FFA", phF),
                            0, 60, 20);
                    }
                }
                plugin.getPlayerManager().addStat(winner.getUniqueId(), "wins", 1);
                plugin.getPlayerManager().addStat(winner.getUniqueId(), "coins", coinReward);
                win.sendMessage(plugin.getConfigManager().prefixed("ffa.coin-reward", "&e+&6{coins} &e{currency} &7(FFA win reward)", java.util.Map.of("coins", String.valueOf(coinReward))));
            }
        }

        // Team-Scoreboard-Color cleanup BEVOR Spectate-Restore — sonst
        // bleiben die [T1]/[T2]-Prefixes nach Match-Ende stehen.
        clearScoreboardTeamsFor(session);

        // Alle TextDisplay-Team-Labels entfernen.
        for (UUID u : session.allParticipants) {
            plugin.getTeamLabelManager().removeLabel(u);
        }

        // Alle Tote-Spectator zurück in Lobby. SpectateManager.stop() macht
        // teleport+setGameMode+setupPlayerInventory synchron.
        plugin.getSpectateManager().endMatch("ffa:" + session.leaderId);

        // Sicherheits-Restore (für ALLE Teilnehmer, auch Winner): wir
        // teleportieren in die Lobby, clearen das Inventar und schedulen
        // den Hotbar-Setup auf den nächsten Tick — so ist der Spieler
        // garantiert in der Lobby-Welt wenn setupPlayerInventory die
        // isInLobbyWorld()-Prüfung macht (sonst kann die Hotbar leer
        // bleiben weil der Cross-World-Teleport noch nicht durch ist).
        Location lobbySpawn = plugin.getArenaManager().getSpawnLocation();
        for (UUID u : session.allParticipants) {
            playerToSession.remove(u);
            frozenFFAPlayers.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) continue;
            final Player pl = p;
            // Tote Spieler stehen auf dem Death-/Respawn-Screen. teleport()
            // greift bei toten Spielern NICHT — sie bleiben in der Arena.
            // Daher: erst respawn() erzwingen, dann teleport im nächsten Tick.
            if (pl.isDead()) {
                try { pl.spigot().respawn(); } catch (Throwable ignored) {}
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!pl.isOnline()) return;
                    if (pl.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
                        pl.setGameMode(org.bukkit.GameMode.SURVIVAL);
                    }
                    if (lobbySpawn != null) plugin.getPlayerManager().safeTeleport(pl, lobbySpawn);
                    DuelManager.clearFullInventory(pl);
                    plugin.getPlayerManager().forceLobbyState(pl);
                    plugin.getPlayerManager().applyLobbyFly(pl);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!pl.isOnline()) return;
                        plugin.getPlayerManager().setupPlayerInventory(pl);
                        plugin.getPlayerManager().refreshQueueSlotItem(pl);
                        plugin.getScoreboardManager().updateScoreboard(pl);
                    }, 2L);
                });
                continue;
            }
            if (pl.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
                pl.setGameMode(org.bukkit.GameMode.SURVIVAL);
            }
            if (lobbySpawn != null) plugin.getPlayerManager().safeTeleport(pl, lobbySpawn);
            DuelManager.clearFullInventory(pl);
            plugin.getPlayerManager().forceLobbyState(pl);
            plugin.getPlayerManager().applyLobbyFly(pl);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!pl.isOnline()) return;
                plugin.getPlayerManager().setupPlayerInventory(pl);
                plugin.getPlayerManager().refreshQueueSlotItem(pl);
                plugin.getScoreboardManager().updateScoreboard(pl);
            }, 2L);
        }
        sessionsByLeader.remove(session.leaderId);

        // Sicht/Tablist wiederherstellen (Match-Peers sind jetzt aufgelöst).
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.getPlayerManager().refreshAllVisibility(), 3L);

        // Multi-Map FFA: reservierte Arena freigeben + Entities killen +
        // Snapshot-Restore. Dadurch können andere Parties die Arena
        // sofort wieder benutzen.
        if (session.reservedArena != null) {
            dev.duels.objects.Arena arena = session.reservedArena;
            // resetArena() killt bereits Nicht-Spieler-Entities (Arrows,
            // gedroppte Items, Crystals etc.) und stellt den Snapshot wieder
            // her — Pendant zum Duell-Ende.
            plugin.getArenaManager().resetArena(arena, () -> arena.setInUse(false));
        }
    }

    /** Sendet einen Title an alle aktuell teilnehmenden Spieler der Session. */
    private void showTitleToSession(FFASession session, String title, String subtitle,
                                    int fadeIn, int stay, int fadeOut) {
        for (UUID u : session.allParticipants) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                p.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
            }
        }
    }

    private void broadcastToSession(FFASession session, String msg) {
        Party party = plugin.getPartyManager().getPartyByLeader(session.leaderId);
        if (party != null) {
            plugin.getPartyManager().broadcast(party, msg);
            return;
        }
        for (UUID u : session.allParticipants) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(plugin.getPrefix() + msg);
        }
    }

    public static class FFASession {
        public final UUID leaderId;
        public final String kitName;
        public final Set<UUID> alive = new HashSet<>();
        public final Set<UUID> allParticipants = new HashSet<>();
        public int graceTicksLeft;
        public BukkitTask graceTask;
        public boolean ended;
        /** Reservierte Arena (Multi-Map FFA). {@code null} = Legacy-Pfad mit globalem Spawn. */
        public dev.duels.objects.Arena reservedArena;
        /**
         * Team-Zuordnung (player -> 1 oder 2). Wenn leer, ist es eine
         * klassische FFA (alle gegen alle). Sonst Team-vs-Team-Modus mit
         * Friendly-Fire-Block und Team-basiertem Sieg-Check.
         */
        public final Map<UUID, Integer> teams = new HashMap<>();

        public FFASession(UUID leaderId, String kitName) {
            this.leaderId = leaderId;
            this.kitName = kitName;
        }

        public boolean isTeamMode() { return !teams.isEmpty(); }
        public int getTeam(UUID uuid) {
            Integer t = teams.get(uuid);
            return t == null ? 0 : t;
        }
    }
}
