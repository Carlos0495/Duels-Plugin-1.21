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

    public PartyFFAManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isParticipant(UUID uuid) {
        return playerToSession.containsKey(uuid);
    }

    public FFASession getSession(UUID uuid) {
        return playerToSession.get(uuid);
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
            p.teleport(spawn);
            DuelManager.clearFullInventory(p);
            plugin.getKitManager().giveKit(p, kitName);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            p.setSaturation(20f);
            if (graceSeconds > 0) {
                p.sendMessage(plugin.getPrefix() + "§6Party FFA §7started! §ePvP enabled in "
                        + graceSeconds + "s§7. §fLast one alive wins.");
            } else {
                p.sendMessage(plugin.getPrefix() + "§6Party FFA §7started! §fLast one alive wins.");
            }
        }

        // Grace-Countdown-Task: zeigt 10/5/4/3/2/1 → GO! Im Anschluss
        // wird graceTicksLeft auf 0 gesetzt; Damage-Pfad checkt das.
        if (graceSeconds > 0) {
            session.graceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (session.graceTicksLeft <= 0) {
                    if (session.graceTask != null) {
                        session.graceTask.cancel();
                        session.graceTask = null;
                    }
                    broadcastToSession(session, "§a§lGO! §7PvP is now enabled.");
                    showTitleToSession(session, "§a§lGO!", "§7PvP enabled", 0, 30, 10);
                    return;
                }
                int secLeft = (session.graceTicksLeft + 19) / 20;
                if (session.graceTicksLeft % 20 == 0) {
                    // Title-Countdown jede Sekunde (User-Wunsch).
                    showTitleToSession(session, "§e§l" + secLeft, "§7PvP in §6" + secLeft + "s", 0, 25, 5);
                    if (secLeft <= 5 || secLeft == 10) {
                        broadcastToSession(session, "§ePvP in §6" + secLeft + "s§7...");
                    }
                }
                session.graceTicksLeft -= 20;
            }, 0L, 20L);
        }
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

        broadcastToSession(session,
                "§c" + dead.getName() + " §7was eliminated. §f" + session.alive.size() + " §7alive.");

        // Auto-Spectate auf einen noch lebenden Teammate (anchor-only,
        // SpectatorMode lässt freies Fliegen sowieso zu).
        UUID anchor = session.alive.isEmpty() ? null : session.alive.iterator().next();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!dead.isOnline()) return;
            if (session.alive.size() <= 1) {
                // Match endet sowieso gleich → ist okay, gleich endSession lassen
            }
            plugin.getSpectateManager().enterAutoSpectateForFFA(dead, anchor, session.leaderId);
        }, 3L);

        if (session.alive.size() <= 1) {
            endSession(session);
        }
    }

    public void handlePlayerQuit(UUID uuid) {
        FFASession session = playerToSession.remove(uuid);
        if (session == null) return;
        session.alive.remove(uuid);
        if (session.alive.size() <= 1) {
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
        return true;
    }

    private void endSession(FFASession session) {
        if (session.ended) return;
        session.ended = true;
        if (session.graceTask != null) {
            try { session.graceTask.cancel(); } catch (Throwable ignored) {}
            session.graceTask = null;
        }

        Player winner = null;
        if (session.alive.size() == 1) {
            UUID winnerId = session.alive.iterator().next();
            winner = Bukkit.getPlayer(winnerId);
            playerToSession.remove(winnerId);
        }
        // Win-Title an alle. Winner getrennt, sonst Subtitle "X gewonnen".
        if (winner != null) {
            broadcastToSession(session, "§6§lWinner: §e" + winner.getName());
            final Player win = winner;
            win.sendTitle("§a§lFFA VICTORY", "§7You won the FFA!", 0, 60, 20);
            for (UUID u : session.allParticipants) {
                if (u.equals(win.getUniqueId())) continue;
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.isOnline()) {
                    p.sendTitle("§c§lDEFEAT", "§7" + win.getName() + " §7won the FFA", 0, 60, 20);
                }
            }
            plugin.getPlayerManager().addStat(winner.getUniqueId(), "wins", 1);
            int coinReward = plugin.getConfigManager().getMainConfig().getInt("coins.win-reward", 10);
            plugin.getPlayerManager().addStat(winner.getUniqueId(), "coins", coinReward);
            win.sendMessage(plugin.getPrefix() + "§e+§6" + coinReward + " §ecoins §7(FFA win reward)");
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
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) continue;
            if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
                p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            }
            if (lobbySpawn != null) p.teleport(lobbySpawn);
            DuelManager.clearFullInventory(p);
            plugin.getPlayerManager().forceLobbyState(p);
            plugin.getPlayerManager().applyLobbyFly(p);
            // 1-Tick-Delay: Cross-World-Teleport wirkt erst nach diesem Tick
            // garantiert auf player.getWorld(), und ein laufendes Spectator-
            // Restore vom endMatch() oben hat dann auch fertig.
            final Player pl = p;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!pl.isOnline()) return;
                plugin.getPlayerManager().setupPlayerInventory(pl);
                plugin.getPlayerManager().refreshQueueSlotItem(pl);
                plugin.getScoreboardManager().updateScoreboard(pl);
            }, 2L);
        }
        sessionsByLeader.remove(session.leaderId);

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

        public FFASession(UUID leaderId, String kitName) {
            this.leaderId = leaderId;
            this.kitName = kitName;
        }
    }
}
