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

        Location spawn = plugin.getArenaManager().getPartyFFASpawn();
        if (spawn == null) {
            return "Party-FFA spawn is not set! Use §e/duels setpartyffaspawn §7as admin.";
        }
        if (!plugin.getKitManager().kitExists(kitName)) {
            return "Kit no longer exists.";
        }

        for (UUID m : party.getMembers()) {
            if (playerToSession.containsKey(m)) {
                return "Party already has an active FFA session.";
            }
        }

        int graceSeconds = plugin.getConfigManager().getMainConfig()
                .getInt("party.ffa-grace-seconds", 10);

        FFASession session = new FFASession(party.getLeader(), kitName);
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
                    return;
                }
                int secLeft = (session.graceTicksLeft + 19) / 20;
                if (session.graceTicksLeft % 20 == 0) {
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
            plugin.getSpectateManager().enterAutoSpectateForFFA(dead, anchor);
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
        if (winner != null) {
            broadcastToSession(session, "§6§lWinner: §e" + winner.getName());
            Location lobby = plugin.getArenaManager().getSpawnLocation();
            if (lobby != null) {
                winner.teleport(lobby);
                DuelManager.clearFullInventory(winner);
                plugin.getPlayerManager().setupPlayerInventory(winner);
                plugin.getPlayerManager().forceLobbyState(winner);
                plugin.getPlayerManager().applyLobbyFly(winner);
            }
            plugin.getPlayerManager().addStat(winner.getUniqueId(), "wins", 1);
        }

        // Alle restlichen Teilnehmer (Tote im Spectator-Modus) zurück in Lobby
        plugin.getSpectateManager().endMatch("ffa:" + session.leaderId);

        // Komplett aus dem playerToSession räumen — auch Spieler die im
        // Spectator-Mode hängen, falls etwas verpasst wurde.
        for (UUID u : session.allParticipants) {
            playerToSession.remove(u);
        }
        sessionsByLeader.remove(session.leaderId);
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

        public FFASession(UUID leaderId, String kitName) {
            this.leaderId = leaderId;
            this.kitName = kitName;
        }
    }
}
