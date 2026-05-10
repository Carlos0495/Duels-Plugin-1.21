package dev.duels.managers;

import dev.duels.DuelsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Verwaltet "/spectate &lt;Spieler&gt;" und den Auto-Spectate-Modus auf
 * Tod in Party-FFA.
 *
 * <p>Beim Eintritt in den Spectator-Modus speichern wir den vorherigen
 * Player-State (Location, GameMode, Health/Food) und teleportieren den
 * Spieler in die Welt des beobachteten Matches in Spectator-GameMode.
 * Beim Match-Ende oder explizitem Verlassen wird der State wieder­
 * hergestellt.</p>
 *
 * <p>Spectator-Spieler werden NICHT als Duel-/FFA-Teilnehmer gezählt;
 * sie können nicht Schaden bekommen oder verursachen (Vanilla-Spectator).
 * Das Plugin filtert sie aus Damage-Listenern heraus, indem
 * {@link #isSpectating(UUID)} im Damage-Pfad ignoriert wird.</p>
 */
public class SpectateManager {

    private final DuelsPlugin plugin;

    /** spectator -> Info über das beobachtete Match. */
    private final Map<UUID, SpectateInfo> spectators = new HashMap<>();

    public SpectateManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isSpectating(UUID uuid) {
        return spectators.containsKey(uuid);
    }

    public SpectateInfo getInfo(UUID uuid) {
        return spectators.get(uuid);
    }

    /**
     * Lässt {@code spectator} das Match von {@code target} beobachten. Liefert
     * eine Fehlermeldung oder {@code null} bei Erfolg.
     */
    public String spectate(Player spectator, Player target) {
        if (spectator == null || target == null) return "Player not found.";
        if (spectator.equals(target)) return "You can't spectate yourself.";
        if (plugin.getDuelManager().isInDuel(spectator.getUniqueId()))
            return "You can't spectate while in a duel.";
        if (plugin.getPartyFFAManager().isParticipant(spectator.getUniqueId()))
            return "You can't spectate while in a Party-FFA.";

        boolean targetInDuel = plugin.getDuelManager().isInDuel(target.getUniqueId());
        boolean targetInFFA = plugin.getPartyFFAManager().isParticipant(target.getUniqueId());
        if (!targetInDuel && !targetInFFA) {
            return target.getName() + " is not in a duel.";
        }

        SpectateInfo existing = spectators.get(spectator.getUniqueId());
        if (existing != null) {
            // Schon im Spectate-Modus: einfach zum neuen Ziel teleportieren.
            existing.targetId = target.getUniqueId();
            existing.matchKey = computeMatchKey(target);
            spectator.teleport(target.getLocation());
            spectator.sendMessage(plugin.getPrefix()
                    + "§7Now spectating §e" + target.getName() + "§7.");
            return null;
        }

        // Pre-Spectate-Snapshot speichern damit wir am Ende sauber zurück
        // restoren können (Inventar wird durch setupPlayerInventory gefüllt,
        // GameMode/Location explicit).
        SpectateInfo info = new SpectateInfo();
        info.previousLocation = spectator.getLocation().clone();
        info.previousGameMode = spectator.getGameMode();
        info.targetId = target.getUniqueId();
        info.matchKey = computeMatchKey(target);
        spectators.put(spectator.getUniqueId(), info);

        spectator.setGameMode(GameMode.SPECTATOR);
        spectator.teleport(target.getLocation());
        spectator.sendMessage(plugin.getPrefix() + "§7Spectating §e"
                + target.getName() + "§7. Use §e/spectate stop §7or §e/spawn §7to leave.");
        return null;
    }

    /**
     * Versetzt einen FFA-Toten direkt in den Spectator-Modus seines eigenen
     * Matches. Pre-Snapshot wird aus der ursprünglichen Lobby genommen
     * (Lobby-Spawn), nicht aus der Death-Location.
     */
    public void enterAutoSpectateForFFA(Player dead, UUID someAliveTeammate, UUID sessionLeaderId) {
        if (dead == null) return;
        SpectateInfo info = new SpectateInfo();
        Location lobby = plugin.getArenaManager().getSpawnLocation();
        info.previousLocation = lobby != null ? lobby.clone() : dead.getLocation();
        info.previousGameMode = GameMode.SURVIVAL;
        info.targetId = someAliveTeammate;
        // matchKey muss zum endMatch("ffa:" + leaderId) Aufruf passen, damit
        // die Spectator beim Session-Ende wieder in SURVIVAL + Lobby kommen.
        info.matchKey = "ffa:" + sessionLeaderId;
        spectators.put(dead.getUniqueId(), info);

        dead.setGameMode(GameMode.SPECTATOR);
        Player anchor = someAliveTeammate != null ? Bukkit.getPlayer(someAliveTeammate) : null;
        if (anchor != null && anchor.isOnline()) {
            dead.teleport(anchor.getLocation());
        }
        dead.sendMessage(plugin.getPrefix() + "§7You are now spectating the FFA. Last alive wins.");
    }

    /** Beendet den Spectate-Modus: Inv/GameMode zurücksetzen, ab in die Lobby. */
    public void stop(Player spectator) {
        if (spectator == null) return;
        SpectateInfo info = spectators.remove(spectator.getUniqueId());
        if (info == null) return;

        spectator.setGameMode(info.previousGameMode != null ? info.previousGameMode : GameMode.SURVIVAL);
        Location lobby = plugin.getArenaManager().getSpawnLocation();
        Location dest = lobby != null ? lobby : info.previousLocation;
        if (dest != null) spectator.teleport(dest);
        plugin.getPlayerManager().forceLobbyState(spectator);
        plugin.getPlayerManager().setupPlayerInventory(spectator);
        plugin.getPlayerManager().applyLobbyFly(spectator);
        spectator.sendMessage(plugin.getPrefix() + "§7You stopped spectating.");
    }

    /** Wird beim Quit aufgerufen, damit wir keinen toten State liegen lassen. */
    public void handlePlayerQuit(UUID uuid) {
        spectators.remove(uuid);
    }

    /**
     * Wird vom Duel-/FFA-Manager beim Match-Ende aufgerufen, damit alle
     * Spectator dieses Matches automatisch in die Lobby kommen.
     */
    public void endMatch(String matchKey) {
        if (matchKey == null) return;
        List<UUID> toRestore = new ArrayList<>();
        for (Map.Entry<UUID, SpectateInfo> e : spectators.entrySet()) {
            if (matchKey.equals(e.getValue().matchKey)) {
                toRestore.add(e.getKey());
            }
        }
        for (UUID u : toRestore) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) stop(p);
            else spectators.remove(u);
        }
    }

    /** Berechnet den Match-Key für einen aktuell duell-/ffa-aktiven Spieler. */
    public String computeMatchKey(Player target) {
        if (target == null) return null;
        var duelSession = plugin.getDuelManager().getDuelSession(target.getUniqueId());
        if (duelSession != null) {
            // Reihenfolge-stabilen Key bauen
            UUID a = duelSession.getPlayer1();
            UUID b = duelSession.getPlayer2();
            if (a != null && b != null && a.compareTo(b) > 0) { UUID t = a; a = b; b = t; }
            return "duel:" + a + ":" + b;
        }
        var ffa = plugin.getPartyFFAManager().getSession(target.getUniqueId());
        if (ffa != null) {
            return "ffa:" + ffa.leaderId;
        }
        return null;
    }

    public static class SpectateInfo {
        public Location previousLocation;
        public GameMode previousGameMode;
        public UUID targetId;
        public String matchKey;
    }
}
