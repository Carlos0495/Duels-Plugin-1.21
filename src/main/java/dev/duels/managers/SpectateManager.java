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
            spectator.sendMessage(plugin.getConfigManager().prefixed("spectate.now-spectating", "&7Now spectating &e{player}&7.", java.util.Map.of("player", target.getName())));
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

        // Teleport ZUERST in die Arena-Welt — Multiverse/Welt-Default kann
        // beim Welt-Wechsel den Gamemode auf SURVIVAL forcen. Erst danach
        // SPECTATOR setzen, mit einer Verzögerung damit alle Listener
        // anderer Plugins durchgelaufen sind. Zusätzlich ein zweiter
        // verzögerter Re-Set falls noch ein Plugin nachträglich Gamemode
        // ändert.
        spectator.teleport(target.getLocation());
        spectator.setGameMode(GameMode.SPECTATOR);
        final UUID specId = spectator.getUniqueId();
        for (long delay : new long[]{1L, 5L, 20L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player pl = Bukkit.getPlayer(specId);
                if (pl == null || !pl.isOnline()) return;
                if (!isSpectating(specId)) return;
                if (pl.getGameMode() != GameMode.SPECTATOR) {
                    pl.setGameMode(GameMode.SPECTATOR);
                }
            }, delay);
        }
        spectator.sendMessage(plugin.getConfigManager().prefixed("spectate.start",
                "&7Spectating &e{player}&7. Use &e/spectate stop &7or &e/spawn &7to leave.",
                java.util.Map.of("player", target.getName())));
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
        dead.sendMessage(plugin.getConfigManager().prefixed("spectate.ffa-auto", "&7You are now spectating the FFA. Last alive wins."));
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
        plugin.getPlayerManager().applyLobbyFly(spectator);
        // 2-Tick-Delay: nach Cross-World-Teleport ist player.getWorld() erst
        // ab nächstem Tick wirklich die Lobby — setupPlayerInventory würde
        // sonst denken wir sind in der Spectate-Welt und keine Hotbar setzen.
        final Player sp = spectator;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sp.isOnline()) plugin.getPlayerManager().setupPlayerInventory(sp);
        }, 2L);
        spectator.sendMessage(plugin.getConfigManager().prefixed("spectate.stop", "&7You stopped spectating."));
    }

    /** Wird beim Quit aufgerufen, damit wir keinen toten State liegen lassen. */
    public void handlePlayerQuit(UUID uuid) {
        spectators.remove(uuid);
    }

    /**
     * Entfernt einen Spieler aus dem Spectate-State OHNE ihn in die Lobby zu
     * teleportieren. Wird verwendet, wenn ein Spectator direkt in ein Match
     * (Duel/FFA/Team) gezogen wird — dort übernimmt der Match-Code Teleport,
     * GameMode und Inventar. Verhindert dass der onGameModeChange-Listener
     * den Wechsel aus SPECTATOR blockiert (User-Bug: Spectator wird nicht
     * ins Duel teleportiert).
     */
    public void clearSpectating(Player spectator) {
        if (spectator == null) return;
        spectators.remove(spectator.getUniqueId());
        if (spectator.getGameMode() == GameMode.SPECTATOR) {
            spectator.setGameMode(GameMode.SURVIVAL);
        }
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

    /** Liefert den gespeicherten Match-Key eines Spectators. */
    public String getMatchKey(UUID spectatorId) {
        SpectateInfo info = spectators.get(spectatorId);
        return info != null ? info.matchKey : null;
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
