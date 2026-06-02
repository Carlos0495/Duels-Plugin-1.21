package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ArenaCommand implements CommandExecutor {

    private final DuelsPlugin plugin;

    public ArenaCommand(DuelsPlugin plugin) {
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
            showUsage(player);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "setfirst" -> handleSetFirst(player, args);
            case "setsecond" -> handleSetSecond(player, args);
            case "setcorner1" -> handleSetCorner1(player, args);
            case "setcorner2" -> handleSetCorner2(player, args);
            case "setffaspawn" -> handleSetFfaSpawn(player, args);
            case "create" -> handleCreate(player, args);
            case "list" -> handleList(player);
            case "delete" -> handleDelete(player, args);
            case "info" -> handleInfo(player, args);
            case "testreset" -> handleTestReset(player, args);
            case "addkit" -> handleAddKit(player, args);
            case "removekit" -> handleRemoveKit(player, args);
            case "clearkits" -> handleClearKits(player, args);
            case "listkits" -> handleListKits(player, args);
            default -> {
                player.sendMessage(plugin.getConfigManager().prefixed("arena.unknown-subcommand", "&cUnknown subcommand!"));
                showUsage(player);
            }
        }

        return true;
    }

    private void showUsage(Player player) {
        player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-header", "&7Usage: /arena <subcommand> [arenaName]"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.subcommands-header", "&7Subcommands:"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-setfirst", "&7  setfirst <arenaName> &8- &aSet first spawn point"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-setsecond", "&7  setsecond <arenaName> &8- &aSet second spawn point"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-setcorner1", "&7  setcorner1 <arenaName> &8- &aSet first corner"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-setcorner2", "&7  setcorner2 <arenaName> &8- &aSet second corner"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-setffaspawn", "&7  setffaspawn <arenaName> &8- &6Set per-arena FFA spawn"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-create", "&7  create <arenaName> &8- &aCreate arena (saves Snapshot)"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-list", "&7  list &8- &aList all arenas"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-delete", "&7  delete <arenaName> &8- &cDelete an arena"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-info", "&7  info <arenaName> &8- &eShow arena info"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-testreset", "&7  testreset <arenaName> &8- &6Test arena reset"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-addkit", "&7  addkit <arenaName> <kit> &8- &aAllow a kit on this arena"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-removekit", "&7  removekit <arenaName> <kit> &8- &cDisallow a kit"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-clearkits", "&7  clearkits <arenaName> &8- &eAllow every kit again"));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.help-listkits", "&7  listkits <arenaName> &8- &bList kits allowed on this arena"));
    }

    private void handleSetFirst(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-setfirst", "&7Usage: /arena setfirst <arenaName>"));
            return;
        }

        String arenaName = args[1];
        Location location = player.getLocation();

        plugin.getArenaManager().setArenaSpawn1(arenaName, location);
        player.sendMessage(plugin.getConfigManager().prefixed("arena.setfirst-success", "&aFirst spawn point set for arena: {arena}", java.util.Map.of("arena", arenaName)));
    }

    private void handleSetSecond(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-setsecond", "&7Usage: /arena setsecond <arenaName>"));
            return;
        }

        String arenaName = args[1];
        Location location = player.getLocation();

        plugin.getArenaManager().setArenaSpawn2(arenaName, location);
        player.sendMessage(plugin.getConfigManager().prefixed("arena.setsecond-success", "&aSecond spawn point set for arena: {arena}", java.util.Map.of("arena", arenaName)));
    }

    private void handleSetFfaSpawn(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-setffaspawn", "&7Usage: /arena setffaspawn <arenaName>"));
            return;
        }
        String arenaName = args[1];
        Location location = player.getLocation();
        plugin.getArenaManager().setArenaFfaSpawn(arenaName, location);
        player.sendMessage(plugin.getConfigManager().prefixed("arena.setffaspawn-success", "&6FFA spawn &7set for arena &e{arena}&7. Multi-Map FFA can use this map now.", java.util.Map.of("arena", arenaName)));
    }

    private void handleSetCorner1(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-setcorner1", "&7Usage: /arena setcorner1 <arenaName>"));
            return;
        }

        String arenaName = args[1];
        Location location = player.getLocation();

        plugin.getArenaManager().setArenaCorner1(arenaName, location);
        player.sendMessage(plugin.getConfigManager().prefixed("arena.setcorner1-success", "&aFirst corner set for arena: {arena}", java.util.Map.of("arena", arenaName)));
    }

    private void handleSetCorner2(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-setcorner2", "&7Usage: /arena setcorner2 <arenaName>"));
            return;
        }

        String arenaName = args[1];
        Location location = player.getLocation();

        plugin.getArenaManager().setArenaCorner2(arenaName, location);
        player.sendMessage(plugin.getConfigManager().prefixed("arena.setcorner2-success", "&aSecond corner set for arena: {arena}", java.util.Map.of("arena", arenaName)));
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-create", "&7Usage: /arena create <arenaName>"));
            return;
        }

        String arenaName = args[1];

        if (plugin.getArenaManager().createArena(arenaName)) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.created", "&aArena '{arena}' created! Snapshot saved.", java.util.Map.of("arena", arenaName)));
        } else {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.create-failed", "&cCould not create arena! Make sure all positions are set."));
        }
    }

    private void handleList(Player player) {
        java.util.List<String> arenas = plugin.getArenaManager().getArenaNames();

        if (arenas.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.none-created", "&cNo arenas created yet!"));
            return;
        }

        player.sendMessage(plugin.getConfigManager().prefixed("arena.list-header", "&6Available Arenas:"));
        for (String arena : arenas) {
            player.sendMessage(plugin.getConfigManager().getMessage("arena.list-entry", "&7- &e{arena}", java.util.Map.of("arena", arena)));
        }
    }

    private void handleDelete(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-delete", "&7Usage: /arena delete <arenaName>"));
            return;
        }

        String arenaName = args[1];

        if (plugin.getArenaManager().deleteArena(arenaName)) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.deleted", "&aArena '{arena}' deleted!", java.util.Map.of("arena", arenaName)));
        } else {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.delete-failed", "&cCould not delete arena! It might be in use or doesn't exist."));
        }
    }

    private void handleInfo(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-info", "&7Usage: /arena info <arenaName>"));
            return;
        }

        String arenaName = args[1];
        dev.duels.objects.Arena arena = plugin.getArenaManager().getArena(arenaName);

        if (arena == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.not-found", "&cArena not found!"));
            return;
        }

        player.sendMessage(plugin.getConfigManager().prefixed("arena.info-header", "&6Arena Info: &e{arena}", java.util.Map.of("arena", arenaName)));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.info-status", "&7Status: {value}", java.util.Map.of("value", arena.isInUse() ? "&cIn Use" : "&aAvailable")));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.info-spawn1", "&7Spawn 1: {value}", java.util.Map.of("value", arena.getSpawn1() != null ? "&aSet" : "&cNot Set")));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.info-spawn2", "&7Spawn 2: {value}", java.util.Map.of("value", arena.getSpawn2() != null ? "&aSet" : "&cNot Set")));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.info-corners", "&7Corners: {value}", java.util.Map.of("value", arena.getCorner1() != null && arena.getCorner2() != null ? "&aSet" : "&cNot Set")));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.info-snapshot", "&7Snapshot: {value}", java.util.Map.of("value", arena.hasSnapshot() ? "&aSaved" : "&cNot Saved")));

        if (arena.getAllowedKits().isEmpty()) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.info-kits-all", "&7Allowed Kits: &aAll"));
        } else {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.info-kits-list", "&7Allowed Kits: &b{kits}", java.util.Map.of("kits", String.join("§7, §b", arena.getAllowedKits()))));
        }
    }

    private void handleTestReset(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-testreset", "&7Usage: /arena testreset <arenaName>"));
            return;
        }

        String arenaName = args[1];
        dev.duels.objects.Arena arena = plugin.getArenaManager().getArena(arenaName);

        if (arena == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.not-found", "&cArena not found!"));
            return;
        }

        if (arena.isInUse()) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.in-use", "&cArena is currently in use!"));
            return;
        }

        if (!arena.hasSnapshot()) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.no-snapshot", "&cThis arena has no snapshot! Create it first."));
            return;
        }

        player.sendMessage(plugin.getConfigManager().prefixed("arena.testreset-start", "&eTesting arena reset for: {arena}", java.util.Map.of("arena", arenaName)));
        player.sendMessage(plugin.getConfigManager().prefixed("arena.testreset-restoring", "&7Restoring snapshot..."));

        arena.setInUse(true);

        plugin.getArenaManager().resetArena(arena, () -> {
            arena.setInUse(false);
            player.sendMessage(plugin.getConfigManager().prefixed("arena.testreset-done", "&aArena reset test completed!"));
        });
    }

    private void handleAddKit(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-addkit", "&7Usage: /arena addkit <arenaName> <kit>"));
            return;
        }

        String arenaName = args[1];
        String kitId = args[2];

        dev.duels.objects.Arena arena = plugin.getArenaManager().getArena(arenaName);
        if (arena == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.not-found", "&cArena not found!"));
            return;
        }

        if (!plugin.getKitManager().kitExists(kitId)) {
            player.sendMessage(plugin.getConfigManager().prefixed("queue.kit-not-found", "&cKit not found: &e{kit}", java.util.Map.of("kit", kitId)));
            return;
        }

        if (!arena.addAllowedKit(kitId)) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.kit-already-allowed", "&eKit &b{kit} &eis already allowed on this arena.", java.util.Map.of("kit", kitId)));
            return;
        }

        plugin.getArenaManager().saveArena(arena);
        player.sendMessage(plugin.getConfigManager().prefixed("arena.kit-added", "&aKit &b{kit} &ais now allowed on arena &e{arena}&a.", java.util.Map.of("kit", kitId, "arena", arenaName)));
    }

    private void handleRemoveKit(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-removekit", "&7Usage: /arena removekit <arenaName> <kit>"));
            return;
        }

        String arenaName = args[1];
        String kitId = args[2];

        dev.duels.objects.Arena arena = plugin.getArenaManager().getArena(arenaName);
        if (arena == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.not-found", "&cArena not found!"));
            return;
        }

        if (!arena.removeAllowedKit(kitId)) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.kit-not-allowed", "&cKit &e{kit} &cwas not allowed on this arena.", java.util.Map.of("kit", kitId)));
            return;
        }

        plugin.getArenaManager().saveArena(arena);
        player.sendMessage(plugin.getConfigManager().prefixed("arena.kit-removed", "&aKit &b{kit} &ais no longer allowed on arena &e{arena}&a.", java.util.Map.of("kit", kitId, "arena", arenaName)));
    }

    private void handleClearKits(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-clearkits", "&7Usage: /arena clearkits <arenaName>"));
            return;
        }

        String arenaName = args[1];
        dev.duels.objects.Arena arena = plugin.getArenaManager().getArena(arenaName);
        if (arena == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.not-found", "&cArena not found!"));
            return;
        }

        arena.clearAllowedKits();
        plugin.getArenaManager().saveArena(arena);
        player.sendMessage(plugin.getConfigManager().prefixed("arena.kits-cleared", "&aArena &e{arena} &anow allows &fall &akits.", java.util.Map.of("arena", arenaName)));
    }

    private void handleListKits(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.usage-listkits", "&7Usage: /arena listkits <arenaName>"));
            return;
        }

        String arenaName = args[1];
        dev.duels.objects.Arena arena = plugin.getArenaManager().getArena(arenaName);
        if (arena == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.not-found", "&cArena not found!"));
            return;
        }

        if (arena.getAllowedKits().isEmpty()) {
            player.sendMessage(plugin.getConfigManager().prefixed("arena.listkits-all", "&7Arena &e{arena} &7allows &aALL &7kits.", java.util.Map.of("arena", arenaName)));
            return;
        }

        player.sendMessage(plugin.getConfigManager().prefixed("arena.listkits-header", "&7Allowed kits for &e{arena}&7:", java.util.Map.of("arena", arenaName)));
        for (String kit : arena.getAllowedKits()) {
            player.sendMessage(plugin.getConfigManager().getMessage("arena.listkits-entry", "&8- &b{kit}", java.util.Map.of("kit", kit)));
        }
    }
}
