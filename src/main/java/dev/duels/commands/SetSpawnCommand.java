package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SetSpawnCommand implements CommandExecutor {

    private final DuelsPlugin plugin;

    public SetSpawnCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().prefixed("general.players-only", "&cOnly players can use this command!"));
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("duels.setspawn")) {
            player.sendMessage(plugin.getConfigManager().prefixed("general.no-permission", "&cYou don't have permission!"));
            return true;
        }

        plugin.getArenaManager().setSpawnLocation(player.getLocation());
        player.sendMessage(plugin.getConfigManager().prefixed("admin.spawn-set", "&a&lSpawn&7 has been &aset!"));
        return true;
    }
}