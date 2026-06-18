package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PingCommand implements CommandExecutor {

    private final DuelsPlugin plugin;

    public PingCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().prefixed("general.players-only", "Only players can use this command!"));
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            // Eigenen Ping anzeigen
            int ping = getPlayerPing(player);
            player.sendMessage(plugin.getConfigManager().prefixed("ping.self", "&7Your ping: &a{ping}ms", java.util.Map.of("ping", String.valueOf(ping))));
        } else {
            // Ping von anderem Spieler anzeigen
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                player.sendMessage(plugin.getConfigManager().prefixed("stats.player-not-found", "&cPlayer not found!"));
                return true;
            }

            int targetPing = getPlayerPing(target);
            player.sendMessage(plugin.getConfigManager().prefixed("ping.other", "&7{player}'s ping: &a{ping}ms", java.util.Map.of("player", target.getName(), "ping", String.valueOf(targetPing))));
        }

        return true;
    }

    private int getPlayerPing(Player player) {
        return player.getPing();
    }
}