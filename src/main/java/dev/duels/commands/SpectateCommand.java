package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class SpectateCommand implements CommandExecutor, TabCompleter {

    private final DuelsPlugin plugin;

    public SpectateCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().prefixed("general.players-only", "&cOnly players can use this command!"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(plugin.getConfigManager().prefixed("spectate.usage", "&eUsage: &7/spectate <player> &7or &7/spectate stop"));
            return true;
        }

        if (args[0].equalsIgnoreCase("stop") || args[0].equalsIgnoreCase("leave") || args[0].equalsIgnoreCase("quit")) {
            if (!plugin.getSpectateManager().isSpectating(player.getUniqueId())) {
                player.sendMessage(plugin.getConfigManager().prefixed("spectate.not-spectating", "&cYou are not spectating anyone."));
                return true;
            }
            plugin.getSpectateManager().stop(player);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(plugin.getConfigManager().prefixed("general.player-not-found", "&cPlayer not found: &f{player}", java.util.Map.of("player", args[0])));
            return true;
        }

        String error = plugin.getSpectateManager().spectate(player, target);
        if (error != null) {
            player.sendMessage(plugin.getConfigManager().prefixed("spectate.error", "&c{error}", java.util.Map.of("error", error)));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                // Nur Spieler vorschlagen die in einem Duel/FFA sind.
                if (!plugin.getDuelManager().isInDuel(p.getUniqueId())
                        && !plugin.getPartyFFAManager().isParticipant(p.getUniqueId())) continue;
                if (p.getName().toLowerCase().startsWith(prefix)) out.add(p.getName());
            }
            if ("stop".startsWith(prefix)) out.add("stop");
        }
        return out;
    }
}
