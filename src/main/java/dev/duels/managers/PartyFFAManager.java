package dev.duels.managers;

import dev.duels.DuelsPlugin;
import dev.duels.objects.Party;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Verwaltet "Party-FFA-Arena"-Sessions: alle Mitglieder einer Party werden
 * an den konfigurierten Party-FFA-Spawn teleportiert, bekommen das gewählte
 * Kit und kämpfen all-vs-all (1 Leben pro Spieler). Wer stirbt, fliegt in
 * die Lobby. Wenn nur noch ein Spieler übrig ist, wird er als Sieger
 * angekündigt und die Session beendet.
 *
 * <p>Diese Klasse bewusst getrennt vom {@link DuelManager}, weil FFA keine
 * 1v1-Logik (Rounds, Best-of, Wins) hat und sonst die Duell-Pipeline
 * verwirren würde.</p>
 */
public class PartyFFAManager {

    private final DuelsPlugin plugin;

    /** playerId -> Session, an der der Spieler gerade teilnimmt. */
    private final Map<UUID, FFASession> playerToSession = new HashMap<>();

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

        // Schon laufende Session in dieser Party? Verhindern.
        for (UUID m : party.getMembers()) {
            if (playerToSession.containsKey(m)) {
                return "Party already has an active FFA session.";
            }
        }

        FFASession session = new FFASession(party.getLeader(), kitName);
        for (Player p : members) {
            if (p == null || !p.isOnline()) continue;
            // Wer schon im Duel ist, wird ausgelassen.
            if (plugin.getDuelManager().isInDuel(p.getUniqueId())) continue;
            session.alive.add(p.getUniqueId());
            playerToSession.put(p.getUniqueId(), session);
        }
        if (session.alive.size() < 2) {
            // Aufräumen
            for (UUID u : session.alive) playerToSession.remove(u);
            return "Need at least 2 online members not currently in a duel.";
        }

        // Teleport + Kit
        for (UUID u : session.alive) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.teleport(spawn);
            p.getInventory().clear();
            plugin.getKitManager().giveKit(p, kitName);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.sendMessage(plugin.getPrefix() + "§6Party FFA §7started! §fLast one alive wins.");
        }
        return null;
    }

    /**
     * Wird aus {@code DuelListener#onPlayerDeath} aufgerufen, wenn der
     * Gestorbene Teilnehmer einer Party-FFA-Session ist. Der Spieler wird
     * aus der Session entfernt und in die Lobby teleportiert. Wenn nur
     * noch ein Spieler übrig ist, wird er als Sieger angekündigt.
     */
    public void handleDeath(Player dead) {
        if (dead == null) return;
        FFASession session = playerToSession.remove(dead.getUniqueId());
        if (session == null) return;
        session.alive.remove(dead.getUniqueId());

        // Lobby-Teleport in zwei Ticks (nach Auto-Respawn)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location lobby = plugin.getArenaManager().getSpawnLocation();
            if (lobby != null && dead.isOnline()) {
                dead.teleport(lobby);
                plugin.getPlayerManager().setupPlayerInventory(dead);
            }
        }, 3L);

        broadcastToSession(session, "§c" + dead.getName() + " §7was eliminated. §f" + session.alive.size() + " §7alive.");

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
     * Erlaubt Teilnehmern derselben Session, sich gegenseitig Schaden
     * zuzufügen. Nicht-Teilnehmer-Treffer werden separat im DuelListener
     * blockiert (wie schon vorher).
     */
    public boolean canDamage(UUID attackerId, UUID targetId) {
        FFASession a = playerToSession.get(attackerId);
        FFASession b = playerToSession.get(targetId);
        return a != null && a == b;
    }

    private void endSession(FFASession session) {
        Player winner = null;
        if (session.alive.size() == 1) {
            UUID winnerId = session.alive.iterator().next();
            winner = Bukkit.getPlayer(winnerId);
            playerToSession.remove(winnerId);
        }
        if (winner != null) {
            broadcastToSession(session, "§6§lWinner: §e" + winner.getName());
            // Sieger zurück in Lobby
            Location lobby = plugin.getArenaManager().getSpawnLocation();
            if (lobby != null) {
                winner.teleport(lobby);
                plugin.getPlayerManager().setupPlayerInventory(winner);
            }
            plugin.getPlayerManager().addStat(winner.getUniqueId(), "wins", 1);
        }
    }

    private void broadcastToSession(FFASession session, String msg) {
        // Sieger-Set kennen wir nicht mehr nach endSession; wir broadcasten
        // an alle aktuellen Member der Party des Leaders, falls erreichbar.
        Party party = plugin.getPartyManager().getPartyByLeader(session.leaderId);
        if (party != null) {
            plugin.getPartyManager().broadcast(party, msg);
            return;
        }
        // Fallback: an alive
        for (UUID u : session.alive) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(plugin.getPrefix() + msg);
        }
    }

    public static class FFASession {
        public final UUID leaderId;
        public final String kitName;
        public final Set<UUID> alive = new HashSet<>();

        public FFASession(UUID leaderId, String kitName) {
            this.leaderId = leaderId;
            this.kitName = kitName;
        }
    }
}
