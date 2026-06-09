package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            sender.sendMessage(plugin.getConfigManager().prefixed("general.players-only", "&cOnly players can use this command!"));
            return true;
        }
        // Admin: /duels:kit give customkit1..4 "Besitzer" to "Empfänger"
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            return handleGive(player, args);
        }

        if (args.length < 2 || !args[0].equalsIgnoreCase("edit")) {
            player.sendMessage(plugin.getConfigManager().prefixed("kitedit.usage", "&eUsage: &7/dkit edit <kitName>"));
            player.sendMessage(plugin.getConfigManager().getMessage("kitedit.usage-give", "&eUsage: &7/duels:kit give customkit1..4 \"owner\" to \"target\""));
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
            player.sendMessage(plugin.getConfigManager().prefixed("queue.kit-not-found", "&cKit not found: &f{kit}", java.util.Map.of("kit", kitId)));
            return true;
        }

        plugin.getGuiManager().openEditLayoutGUI(player, kitId);
        return true;
    }

    private boolean handleGive(Player player, String[] args) {
        if (!player.hasPermission("duels.admin")) {
            player.sendMessage(plugin.getConfigManager().prefixed("general.no-permission", "&cYou don't have permission to do that!"));
            return true;
        }
        if (args.length < 4) {
            player.sendMessage(plugin.getConfigManager().prefixed("kitedit.usage-give", "&eUsage: &7/duels:kit give customkit1..4 \"owner\" to \"target\""));
            return true;
        }

        String slotArg = args[1].toLowerCase(Locale.ROOT);
        if (!slotArg.matches("customkit[1-4]")) {
            player.sendMessage(plugin.getConfigManager().prefixed("kitedit.give-bad-slot", "&cKit slot must be customkit1, customkit2, customkit3 or customkit4."));
            return true;
        }
        int slot = Integer.parseInt(slotArg.substring("customkit".length())); // 1..4

        // Rest zusammensetzen und Anführungszeichen (auch typographische) normalisieren.
        StringBuilder rb = new StringBuilder();
        for (int i = 2; i < args.length; i++) { if (i > 2) rb.append(' '); rb.append(args[i]); }
        String rest = rb.toString()
                .replace('\u201e', '"').replace('\u201c', '"').replace('\u201d', '"')
                .replace('\u00ab', '"').replace('\u00bb', '"').replace('\u2018', '"').replace('\u2019', '"');

        String ownerName;
        String targetName;
        if (rest.indexOf('"') >= 0) {
            List<String> quoted = new ArrayList<>();
            Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(rest);
            while (m.find()) quoted.add(m.group(1).trim());
            if (quoted.size() < 2) {
                player.sendMessage(plugin.getConfigManager().prefixed("kitedit.usage-give", "&eUsage: &7/duels:kit give customkit1..4 \"owner\" to \"target\""));
                return true;
            }
            ownerName = quoted.get(0);
            targetName = quoted.get(1);
        } else {
            String[] parts = rest.split("(?i)\\s+to\\s+", 2);
            if (parts.length < 2) {
                player.sendMessage(plugin.getConfigManager().prefixed("kitedit.usage-give", "&eUsage: &7/duels:kit give customkit1..4 \"owner\" to \"target\""));
                return true;
            }
            ownerName = parts[0].trim();
            targetName = parts[1].trim();
        }

        if (ownerName.isEmpty() || targetName.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().prefixed("kitedit.usage-give", "&eUsage: &7/duels:kit give customkit1..4 \"owner\" to \"target\""));
            return true;
        }

        UUID ownerId = resolveUuid(ownerName);
        UUID targetId = resolveUuid(targetName);
        if (ownerId == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("kitedit.give-unknown-player", "&cUnknown player: &e{player}", java.util.Map.of("player", ownerName)));
            return true;
        }
        if (targetId == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("kitedit.give-unknown-player", "&cUnknown player: &e{player}", java.util.Map.of("player", targetName)));
            return true;
        }

        dev.duels.managers.CustomKitManager.GiveResult result =
                plugin.getCustomKitManager().giveKit(ownerId, slot, targetId);
        if (result == dev.duels.managers.CustomKitManager.GiveResult.NO_SUCH_KIT) {
            player.sendMessage(plugin.getConfigManager().prefixed("kitedit.give-no-kit", "&c{owner} has no custom kit in slot {slot}.",
                    java.util.Map.of("owner", ownerName, "slot", String.valueOf(slot))));
            return true;
        }
        player.sendMessage(plugin.getConfigManager().prefixed("kitedit.give-success",
                "&aCopied custom kit #{slot} from &e{owner} &ato &e{target}&a.",
                java.util.Map.of("slot", String.valueOf(slot), "owner", ownerName, "target", targetName)));
        return true;
    }

    /** Resolve a player name to a UUID (online first, then known offline players). */
    private UUID resolveUuid(String name) {
        Player online = org.bukkit.Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        org.bukkit.OfflinePlayer off = org.bukkit.Bukkit.getOfflinePlayer(name);
        if (off != null && (off.hasPlayedBefore() || off.isOnline()) && off.getUniqueId() != null) {
            return off.getUniqueId();
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            if ("edit".startsWith(prefix)) out.add("edit");
            if ("give".startsWith(prefix)) out.add("give");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase();
            for (String s : new String[]{"customkit1", "customkit2", "customkit3", "customkit4"}) {
                if (s.startsWith(prefix)) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
            String prefix = args[1].toLowerCase();
            for (String kit : plugin.getKitManager().getKitNames()) {
                if (kit.toLowerCase().startsWith(prefix)) out.add(kit);
            }
        }
        return out;
    }
}
