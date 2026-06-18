package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SpawnCommand implements CommandExecutor {

    private final DuelsPlugin plugin;

    public SpawnCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        dev.duels.managers.ConfigManager cm = plugin.getConfigManager();
        if (!(sender instanceof Player)) {
            sender.sendMessage(cm.prefixed("general.players-only", "Only players can use this command!"));
            return true;
        }

        Player player = (Player) sender;
        java.util.UUID uuid = player.getUniqueId();

        // Check if player is in a duel (1v1)
        if (plugin.getDuelManager().isInDuel(uuid)) {
            plugin.getDuelManager().handleForfeit(player);
            return true;
        }

        // FFA/Team: wenn noch alive → als "freiwillig ausgeschieden"
        // behandeln (Spectator-Modus, bleibt in Party). Zweites /spawn
        // (wenn schon Spectator) → zurück zum Spawn.
        if (plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(uuid)) {
            var ffaSess = plugin.getPartyFFAManager().getSession(uuid);
            if (ffaSess != null && ffaSess.alive.contains(uuid)) {
                // Spieler ist noch alive → eliminate und in Spectator setzen
                plugin.getPartyFFAManager().voluntaryLeave(player);
                player.sendMessage(cm.prefixed("spawn.left-match",
                        "&7You left the match. Use &e/spawn &7again to return to spawn."));
                return true;
            }
            // Spieler ist bereits tot/spectator → Spectator stoppen
            // und zum Spawn teleportieren (bleibt aber in der Party).
        }

        // Spectator: sauber beenden statt nur teleportieren
        if (plugin.getSpectateManager() != null
                && plugin.getSpectateManager().isSpectating(uuid)) {
            plugin.getSpectateManager().stop(player);
            return true;
        }

        // Normal spawn teleport
        plugin.getPlayerManager().teleportToSpawn(player);
        return true;
    }
}