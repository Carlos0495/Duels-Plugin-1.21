package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Direkter Shortcut zur Kit-Layout-Edit-GUI.
 *
 * <p>Aufruf: {@code /dkit edit <kitName>} oder {@code /duels:kit edit <kitName>}.
 * Öffnet dieselbe GUI wie der bisherige Pfad über {@code /settings → Kits →
 * Klick}, ohne dass der Nutzer Klicks zählen muss.</p>
 */
public class KitEditCommand implements CommandExecutor, TabCompleter {

    private final DuelsPlugin plugin;

    public KitEditCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("edit")) {
            player.sendMessage(plugin.getPrefix() + "§eUsage: §7/dkit edit <kitName>");
            return true;
        }

        // Quote-Argumente zusammenfügen: /dkit edit "Crystal PvP" → "Crystal PvP"
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) sb.append(' ');
            sb.append(args[i]);
        }
        String kitId = sb.toString().trim().replace("\"", "");

        if (!plugin.getKitManager().kitExists(kitId)) {
            player.sendMessage(plugin.getPrefix() + "§cKit not found: §f" + kitId);
            return true;
        }

        plugin.getGuiManager().openEditLayoutGUI(player, kitId);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            if ("edit".startsWith(prefix)) out.add("edit");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
            String prefix = args[1].toLowerCase();
            for (String kit : plugin.getKitManager().getKitNames()) {
                if (kit.toLowerCase().startsWith(prefix)) out.add(kit);
            }
        }
        return out;
    }
}
