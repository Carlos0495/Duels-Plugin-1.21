package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MainCommand implements CommandExecutor {

    private final DuelsPlugin plugin;

    public MainCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("duels.reload")) {
                sender.sendMessage(plugin.getPrefix() + "§cYou don't have permission!");
                return true;
            }

            reloadPlugin(sender);
            return true;
        }

        // /duels invlayout  -> öffnet Edit-Layouts GUI (Kit-Auswahl) damit man
        // dort die persönliche Inventar-Anordnung pro Kit anpasst.
        if (args.length > 0 && (args[0].equalsIgnoreCase("invlayout")
                || args[0].equalsIgnoreCase("editlayout")
                || args[0].equalsIgnoreCase("kitlayout"))) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(plugin.getPrefix() + "§cThis command requires a player.");
                return true;
            }
            plugin.getGuiManager().openEditLayoutsGUI(p);
            return true;
        }

        // /duels armortrim  -> öffnet den Armor-Trim-Editor wenn Permission da
        // ist, sonst kurze Meldung (kein Crash, kein GUI).
        if (args.length > 0 && (args[0].equalsIgnoreCase("armortrim")
                || args[0].equalsIgnoreCase("armortrims")
                || args[0].equalsIgnoreCase("trim"))) {
            // /duels armortrim clear <player|all> — Admin-Befehl
            if (args.length >= 3 && args[1].equalsIgnoreCase("clear")) {
                if (!sender.hasPermission("duels.admin")) {
                    sender.sendMessage(plugin.getPrefix() + "§cNo permission.");
                    return true;
                }
                String targetArg = args[2];
                if (targetArg.equalsIgnoreCase("all")) {
                    int count = 0;
                    for (Player op : org.bukkit.Bukkit.getOnlinePlayers()) {
                        clearTrimsForPlayer(op);
                        count++;
                    }
                    sender.sendMessage(plugin.getPrefix() + "§7Cleared armor trims for §f" + count + " §7players.");
                } else {
                    Player target = org.bukkit.Bukkit.getPlayerExact(targetArg);
                    if (target == null) {
                        // Auch Offline-Spieler unterstützen via UUID-Lookup
                        java.util.UUID offUuid = plugin.getPlayerManager().getUUIDFromName(targetArg);
                        if (offUuid != null) {
                            for (dev.duels.managers.ArmorTrimManager.Piece piece : dev.duels.managers.ArmorTrimManager.Piece.values()) {
                                plugin.getArmorTrimManager().clearPiece(offUuid, piece);
                            }
                            sender.sendMessage(plugin.getPrefix() + "§7Cleared armor trims for §f" + targetArg + " §7(offline).");
                        } else {
                            sender.sendMessage(plugin.getPrefix() + "§cPlayer not found: §f" + targetArg);
                        }
                        return true;
                    }
                    clearTrimsForPlayer(target);
                    sender.sendMessage(plugin.getPrefix() + "§7Cleared armor trims for §f" + target.getName() + "§7.");
                }
                return true;
            }

            if (!(sender instanceof Player p)) {
                sender.sendMessage(plugin.getPrefix() + "§cThis command requires a player.");
                return true;
            }
            if (!p.hasPermission(dev.duels.managers.ArmorTrimManager.PERMISSION)) {
                p.sendMessage(plugin.getPrefix() + "§cYou don't have permission to use armor trims.");
                return true;
            }
            plugin.getGuiManager().openArmorTrimGUI(p);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("setpartyffaspawn")) {
            if (!sender.hasPermission("duels.admin")) {
                sender.sendMessage(plugin.getPrefix() + "§cYou don't have permission!");
                return true;
            }
            if (!(sender instanceof Player p)) {
                sender.sendMessage(plugin.getPrefix() + "§cThis command requires a player.");
                return true;
            }
            plugin.getArenaManager().setPartyFFASpawn(p.getLocation());
            p.sendMessage(plugin.getPrefix() + "§aParty-FFA spawn set to your current location.");
            return true;
        }

        // Hilfe anzeigen
        sender.sendMessage(plugin.getPrefix() + "§aDuels Plugin by Ryhox.");
        sender.sendMessage("§3Commands:");
        sender.sendMessage("§c/duels reload §8- §7Reload the plugin configuration");
        sender.sendMessage("§c/spawn §8- §7Teleport to spawn");
        sender.sendMessage("§c/stats [player] §8- §7Check stats");
        sender.sendMessage("§c/duel <player> §8- §7Challenge a player to a duel");
        sender.sendMessage("§c/kits §8- §7View available kits");
        sender.sendMessage("§c/accept §8- §7Accept a pending duel request");
        sender.sendMessage("§c/ping <player> §8- §7Check ping");
        sender.sendMessage("§c/previewkit <kitname> §8- §7Preview a kit in your inventory");
        sender.sendMessage("§c/queue join <kit> §8- §7Join a queue");
        sender.sendMessage("§c/queue gui §8- §7Open the kit GUI");
        sender.sendMessage("§c/queue leave §8- §7Leave a queue");
        sender.sendMessage("§c/duels invlayout §8- §7Edit your per-kit inventory layout");
        sender.sendMessage("§c/duels armortrim §8- §7Open the armor trim editor (needs duels.armortrim)");

        if (sender.hasPermission("duels.admin")) {
            sender.sendMessage("§c/setspawn §8- §7Set spawn location");
            sender.sendMessage("§c/duels setpartyffaspawn §8- §7Set Party-FFA arena spawn");
            sender.sendMessage("§c/setkills <player> <amount> §8- §7Set player kills");
            sender.sendMessage("§c/setdeaths <player> <amount> §8- §7Set player deaths");
            sender.sendMessage("§c/setwins <player> <amount> §8- §7Set player wins");
            sender.sendMessage("§c/setlosses <player> <amount> §8- §7Set player losses");
            sender.sendMessage("§c/addkit <name> <preview_item> §8- §7Create a new kit");
            sender.sendMessage("§c/arenacreate §8- §7Create and manage arenas");
        }

        return true;
    }

    private void reloadPlugin(CommandSender sender) {
        plugin.getConfigManager().loadAllConfigs();
        plugin.getArenaManager().loadArenas();
        plugin.getKitManager().loadKits();
        plugin.getPlayerManager().loadPlayerData();
        // Hotbar-Config muss ebenfalls neu eingelesen werden, sonst greifen
        // Config-Änderungen (Material/Slot/Action) NIE ohne Server-Restart.
        plugin.getHotbarManager().loadHotbarConfig();
        plugin.getPartyManager().loadConfig();
        if (plugin.getGuiConfig() != null) plugin.getGuiConfig().load();

        // Placeholders neu beim TAB-Plugin registrieren — falls TAB inzwischen
        // gestartet ist oder seine eigene Placeholder-Map durch /tab reload
        // zurückgesetzt wurde, ist %duels_status% sonst weg.
        plugin.registerDuelsPlaceholders();
        plugin.registerTabPlaceholders();

        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            plugin.getScoreboardManager().updateScoreboard(player);
            // Re-apply Lobby-Hotbar an alle Lobby-Spieler, damit gelöschte
            // Hotbar-Items sofort weg sind und neue erscheinen.
            try {
                if (plugin.getPlayerManager().isInLobbyWorld(player)
                        && !plugin.getDuelManager().isInDuel(player.getUniqueId())) {
                    String mode = plugin.getPartyManager().isLeader(player.getUniqueId())
                            ? dev.duels.managers.HotbarManager.MODE_PARTY_LEADER
                            : plugin.getPartyManager().isInParty(player.getUniqueId())
                                ? dev.duels.managers.HotbarManager.MODE_PARTY_MEMBER
                                : dev.duels.managers.HotbarManager.MODE_LOBBY;
                    plugin.getHotbarManager().applyMode(player, mode);
                }
            } catch (Throwable ignored) {}
        }

        sender.sendMessage(plugin.getPrefix() + "§7Plugin configuration §a§lreloaded!");
        plugin.getLogger().info("Duels plugin reloaded by " + sender.getName());
    }

    private void clearTrimsForPlayer(Player target) {
        java.util.UUID uuid = target.getUniqueId();
        for (dev.duels.managers.ArmorTrimManager.Piece piece : dev.duels.managers.ArmorTrimManager.Piece.values()) {
            plugin.getArmorTrimManager().clearPiece(uuid, piece);
        }
        target.sendMessage(plugin.getPrefix() + "§7Your armor trims have been §ccleared §7by an admin.");
    }
}