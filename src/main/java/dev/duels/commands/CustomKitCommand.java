package dev.duels.commands;

import dev.duels.DuelsPlugin;
import dev.duels.managers.CustomKitManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Player command {@code /customkit}:
 * <ul>
 *   <li>no args → opens the player's own custom kit list GUI</li>
 *   <li>{@code copy <owner>} → copy another player's custom kit into a free slot
 *       (only possible when the player still has a free kit slot)</li>
 * </ul>
 */
public class CustomKitCommand implements CommandExecutor, TabCompleter {

    private final DuelsPlugin plugin;

    public CustomKitCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().prefixed("general.players-only", "&cOnly players can use this command!"));
            return true;
        }

        if (args.length == 0) {
            plugin.getGuiManager().openCustomKitListGUI(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("copy")) {
            return handleCopy(player, args);
        }

        plugin.getGuiManager().openCustomKitListGUI(player);
        return true;
    }

    private boolean handleCopy(Player player, String[] args) {
        CustomKitManager ckm = plugin.getCustomKitManager();

        if (ckm.getKitLimit(player) <= 0) {
            player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.no-permission",
                    "&cYou don't have permission to create custom kits."));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-usage",
                    "&eUsage: &7/customkit copy <owner> [number]"));
            return true;
        }

        String ownerName = args[1];
        UUID ownerId = resolveUuid(ownerName);
        if (ownerId == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-unknown-player",
                    "&cUnknown player: &e{player}", Map.of("player", ownerName)));
            return true;
        }
        if (ownerId.equals(player.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-self",
                    "&cYou can't copy your own kit."));
            return true;
        }

        // No free slot? Tell the player up-front.
        if (ckm.getKitCount(player.getUniqueId()) >= ckm.getKitLimit(player)) {
            player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-no-slot",
                    "&cYou have no free custom kit slot to copy into."));
            return true;
        }

        var ownerKits = ckm.getKits(ownerId);
        if (ownerKits.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-owner-empty",
                    "&e{owner} &chas no custom kits.", Map.of("owner", ownerName)));
            return true;
        }

        // Optional explicit kit number (1-based position in the owner's list).
        if (args.length >= 3) {
            int pos;
            try { pos = Integer.parseInt(args[2]); } catch (NumberFormatException e) { pos = -1; }
            if (pos < 1 || pos > ownerKits.size()) {
                player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-bad-number",
                        "&c{owner} only has {count} custom kit(s).",
                        Map.of("owner", ownerName, "count", String.valueOf(ownerKits.size()))));
                return true;
            }
            doCopy(player, ownerId, ownerName, ownerKits.get(pos - 1).getIndex());
            return true;
        }

        // Exactly one kit → copy directly; otherwise open a selection GUI.
        if (ownerKits.size() == 1) {
            doCopy(player, ownerId, ownerName, ownerKits.get(0).getIndex());
        } else {
            plugin.getGuiManager().openCopyKitGUI(player, ownerId, ownerName);
        }
        return true;
    }

    private void doCopy(Player player, UUID ownerId, String ownerName, int ownerKitIndex) {
        CustomKitManager.CopyResult result =
                plugin.getCustomKitManager().copyKitToSelf(player, ownerId, ownerKitIndex);
        switch (result) {
            case SUCCESS -> player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-success",
                    "&aCopied a custom kit from &e{owner} &ainto your kits.", Map.of("owner", ownerName)));
            case NO_SLOT -> player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-no-slot",
                    "&cYou have no free custom kit slot to copy into."));
            case NO_PERMISSION -> player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.no-permission",
                    "&cYou don't have permission to create custom kits."));
            case NO_SUCH_KIT -> player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-owner-empty",
                    "&e{owner} &chas no custom kits.", Map.of("owner", ownerName)));
        }
    }

    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        if (off != null && (off.hasPlayedBefore() || off.isOnline()) && off.getUniqueId() != null) {
            return off.getUniqueId();
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if ("copy".startsWith(args[0].toLowerCase(Locale.ROOT))) out.add("copy");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("copy")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.equals(sender)) continue;
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }
}
