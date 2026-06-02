package dev.duels.commands;

import dev.duels.DuelsPlugin;
import dev.duels.managers.KitManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Set;

public class KitCommand implements CommandExecutor {

    private final DuelsPlugin plugin;

    public KitCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().prefixed("general.players-only", "&cOnly players can use this command!"));
            return true;
        }

        if (!player.hasPermission("duels.admin")) {
            player.sendMessage(plugin.getConfigManager().prefixed("general.no-permission", "&cYou don't have permission!"));
            return true;
        }

        if (args.length < 1) {
            sendUsage(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("add")) {
            return handleAdd(player, args);
        }

        if (sub.equals("remove") || sub.equals("delete")) {
            return handleRemove(player, args);
        }

        sendUsage(player);
        return true;
    }

    private boolean handleAdd(Player player, String[] args) {
        // /kit add <name with spaces and &> <material>
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().prefixed("kit.usage-add", "&7Usage: /kit add <name> <preview_item>"));
            player.sendMessage(plugin.getConfigManager().prefixed("kit.example-add", "&7Example: /kit add &a&lSword DIAMOND_SWORD"));
            return true;
        }

        String previewItemStr = args[args.length - 1].toUpperCase(Locale.ROOT);
        Material previewMaterial;
        try {
            previewMaterial = Material.valueOf(previewItemStr);
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getConfigManager().prefixed("kit.invalid-material", "&cInvalid material! Use something like DIAMOND_SWORD, BOW, etc."));
            return true;
        }

        String rawName = join(args, 1, args.length - 1); // join args[1..len-2]
        String displayName = ChatColor.translateAlternateColorCodes('&', rawName);

        String plainName = ChatColor.stripColor(displayName);
        if (plainName == null) plainName = "";
        plainName = plainName.trim();

        if (plainName.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().prefixed("kit.invalid-name", "&cInvalid kit name!"));
            return true;
        }

        String kitId = toKitId(plainName);

        if (plugin.getKitManager().kitExists(kitId)) {
            KitManager.Kit existing = plugin.getKitManager().getKit(kitId);
            String existingDisplay = existing != null ? existing.getDisplayName() : kitId;
            player.sendMessage(plugin.getConfigManager().prefixed("kit.already-exists", "&cKit already exists: {display} &8(&f{id}&8)", java.util.Map.of("display", existingDisplay, "id", kitId)));
            player.sendMessage(plugin.getConfigManager().prefixed("kit.remove-first", "&7Use &c/kit remove {name} &7first.", java.util.Map.of("name", plainName)));
            return true;
        }

        KitManager.Kit kit = new KitManager.Kit(kitId);
        kit.setDisplayName(displayName);
        kit.setPreviewMaterial(previewMaterial);

        // Main inventory (0-35)
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < 36; i++) {
            if (contents[i] != null && contents[i].getType() != Material.AIR) {
                kit.setItem(i, contents[i].clone());
            }
        }

        // Armor (100-103)
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null && armor[i].getType() != Material.AIR) {
                kit.setItem(100 + i, armor[i].clone());
            }
        }

        // Offhand (99)
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() != Material.AIR) {
            kit.setItem(99, offhand.clone());
        }

        plugin.getKitManager().saveKit(kitId, kit);

        player.sendMessage(plugin.getConfigManager().prefixed("kit.created", "&7Kit {display} &7has been &a&lcreated!", java.util.Map.of("display", kit.getDisplayName())));
        player.sendMessage(plugin.getConfigManager().prefixed("kit.internal-name", "&7Internal name: &f{id}", java.util.Map.of("id", kitId)));
        return true;
    }

    private boolean handleRemove(Player player, String[] args) {
        // /kit remove <name with spaces (can include colors, ignored)>
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("kit.usage-remove", "&7Usage: /kit remove <name>"));
            player.sendMessage(plugin.getConfigManager().prefixed("kit.example-remove", "&7Example: /kit remove No Debuff Kit"));
            return true;
        }

        String raw = join(args, 1, args.length); // all after "remove"
        String colored = ChatColor.translateAlternateColorCodes('&', raw);
        String plain = ChatColor.stripColor(colored);
        if (plain == null) plain = "";
        plain = plain.trim();

        if (plain.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().prefixed("kit.invalid-name", "&cInvalid kit name!"));
            return true;
        }

        String kitId = toKitId(plain);

        // if user typed exact kitId, allow it too
        if (!plugin.getKitManager().kitExists(kitId)) {
            // try direct match by iterating displayNames (optional but helpful)
            String resolved = resolveKitIdByDisplayOrId(raw);
            if (resolved != null) kitId = resolved;
        }

        if (!plugin.getKitManager().kitExists(kitId)) {
            player.sendMessage(plugin.getConfigManager().prefixed("queue.kit-not-found", "&cKit not found: &f{kit}", java.util.Map.of("kit", plain)));
            return true;
        }

        KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
        String display = kit != null ? kit.getDisplayName() : kitId;

        plugin.getKitManager().deleteKit(kitId);

        player.sendMessage(plugin.getConfigManager().prefixed("kit.removed", "&7Kit {display} &7has been &c&lremoved!", java.util.Map.of("display", display)));
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(plugin.getConfigManager().prefixed("kit.help-usage", "&7Usage:"));
        player.sendMessage(plugin.getConfigManager().prefixed("kit.help-add", "&7- /kit add <name> <preview_item>"));
        player.sendMessage(plugin.getConfigManager().prefixed("kit.help-remove", "&7- /kit remove <name>"));
        player.sendMessage(plugin.getConfigManager().prefixed("kit.help-examples", "&7Examples:"));
        player.sendMessage(plugin.getConfigManager().prefixed("kit.help-ex1", "&7- /kit add &a&lSword DIAMOND_SWORD"));
        player.sendMessage(plugin.getConfigManager().prefixed("kit.help-ex2", "&7- /kit add &bNo Debuff Kit NETHERITE_SWORD"));
        player.sendMessage(plugin.getConfigManager().prefixed("kit.help-ex3", "&7- /kit remove No Debuff Kit"));
    }

    private static String join(String[] args, int fromInclusive, int toExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (i > fromInclusive) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static String toKitId(String plainName) {
        // stable id: lowercase, spaces to _, remove weird chars
        String id = plainName.toLowerCase(Locale.ROOT).replace(' ', '_');
        id = id.replaceAll("[^a-z0-9_\\-]", ""); // keep a-z 0-9 _ -
        while (id.contains("__")) id = id.replace("__", "_");
        if (id.startsWith("_")) id = id.substring(1);
        if (id.endsWith("_")) id = id.substring(0, id.length() - 1);
        return id.isEmpty() ? "kit" : id;
    }

    private String resolveKitIdByDisplayOrId(String input) {
        String inputColored = ChatColor.translateAlternateColorCodes('&', input);
        String inputPlain = ChatColor.stripColor(inputColored);
        if (inputPlain == null) inputPlain = "";
        inputPlain = inputPlain.trim();

        Set<String> ids = plugin.getKitManager().getKitNames();
        for (String id : ids) {
            if (id.equalsIgnoreCase(input.trim())) return id;

            KitManager.Kit kit = plugin.getKitManager().getKit(id);
            if (kit == null) continue;

            String kitPlain = ChatColor.stripColor(kit.getDisplayName());
            if (kitPlain == null) kitPlain = "";
            if (kitPlain.trim().equalsIgnoreCase(inputPlain)) return id;
        }
        return null;
    }
}
