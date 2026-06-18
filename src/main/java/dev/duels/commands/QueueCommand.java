package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class QueueCommand implements CommandExecutor {

    private final DuelsPlugin plugin;

    public QueueCommand(DuelsPlugin plugin) {
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
            showUsage(player);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "leave":
                handleLeave(player);
                break;

            case "gui":
                handleGUI(player);
                break;

            case "join":
                handleJoin(player, args);
                break;

            default:
                player.sendMessage(plugin.getConfigManager().prefixed("queue.unknown-subcommand", "&cUnknown subcommand!"));
                showUsage(player);
                break;
        }

        return true;
    }

    private void showUsage(Player player) {
        player.sendMessage(plugin.getConfigManager().prefixed("queue.usage-header", "&7Usage:"));
        player.sendMessage(plugin.getConfigManager().getMessage("queue.usage-join", "&c/queue join <kit>"));
        player.sendMessage(plugin.getConfigManager().getMessage("queue.usage-leave", "&c/queue leave"));
        player.sendMessage(plugin.getConfigManager().getMessage("queue.usage-gui", "&c/queue gui"));
    }

    private void handleLeave(Player player) {
        plugin.getQueueManager().leaveQueue(player);
        plugin.getPlayerManager().refreshQueueSlotItem(player);
    }

    private void handleGUI(Player player) {
        plugin.getGuiManager().openQueueGUI(player);
    }

    private void handleJoin(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("queue.usage-join", "&7Usage: &c/queue join <kit>"));
            return;
        }

        String kitName = args[1];

        if (!plugin.getKitManager().kitExists(kitName)) {
            player.sendMessage(plugin.getConfigManager().prefixed("queue.kit-not-found", "&cKit not found: {kit}", java.util.Map.of("kit", kitName)));
            return;
        }

        // Toggle behavior
        if (plugin.getQueueManager().isInQueue(player.getUniqueId(), kitName)) {
            plugin.getQueueManager().leaveQueue(player);
            player.sendMessage(plugin.getConfigManager().prefixed("queue.left-kit", "&cLeft &7queue for kit &e{kit}", java.util.Map.of("kit", kitName)));
        } else {
            plugin.getQueueManager().joinQueue(player, kitName);
        }

        plugin.getPlayerManager().refreshQueueSlotItem(player);
    }
}