package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SetStatsCommand implements CommandExecutor {

    private final DuelsPlugin plugin;

    public SetStatsCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("duels.admin")) {
            sender.sendMessage(plugin.getPrefix() + "§cYou don't have permission!");
            return true;
        }

        if (args.length != 2) {
            sender.sendMessage(plugin.getPrefix() + "§7Usage: §c/" + label + " <player|all> <amount>");
            return true;
        }

        String targetArg = args[0];
        int amount;

        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getPrefix() + "§cInvalid amount!");
            return true;
        }

        String statType = command.getName().toLowerCase().replace("set", "");

        // Bulk-Modus: "all" wendet das Stat-Update auf jeden Spieler in
        // players.yml an (online UND offline).
        if (targetArg.equalsIgnoreCase("all")) {
            List<UUID> targets = collectAllKnownPlayerUUIDs();
            if (targets.isEmpty()) {
                sender.sendMessage(plugin.getPrefix() + "§cNo players found in players.yml!");
                return true;
            }
            int n = 0;
            for (UUID uuid : targets) {
                try {
                    plugin.getPlayerManager().setStat(uuid, statType, amount);
                    org.bukkit.entity.Player online = Bukkit.getPlayer(uuid);
                    if (online != null) plugin.getScoreboardManager().updateScoreboard(online);
                    n++;
                } catch (Throwable ignored) {}
            }
            sender.sendMessage(plugin.getPrefix() + "§a" + capitalize(statType) + " §7set to §e"
                    + amount + " §7for §e" + n + " §7player(s) §8(online + offline).");
            return true;
        }

        // Single-Player-Modus (wie vorher)
        UUID targetUUID = plugin.getPlayerManager().getUUIDFromName(targetArg);
        if (targetUUID == null) {
            // Falls Spieler nie auf dem Server war: per OfflinePlayer ID auflösen.
            try {
                @SuppressWarnings("deprecation")
                org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(targetArg);
                if (offline.getUniqueId() != null) targetUUID = offline.getUniqueId();
            } catch (Throwable ignored) {}
        }
        if (targetUUID == null) {
            sender.sendMessage(plugin.getPrefix() + "§cPlayer not found!");
            return true;
        }

        plugin.getPlayerManager().setStat(targetUUID, statType, amount);

        sender.sendMessage(plugin.getPrefix() + "§a" + capitalize(statType)
                + " §7of §e" + targetArg + " §7set to §e" + amount + "§7.");

        org.bukkit.entity.Player targetPlayer = Bukkit.getPlayer(targetUUID);
        if (targetPlayer != null) {
            plugin.getScoreboardManager().updateScoreboard(targetPlayer);
        }

        return true;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Holt UUIDs aller registrierten Spieler aus players.yml (offline + online).
     */
    private List<UUID> collectAllKnownPlayerUUIDs() {
        List<UUID> out = new ArrayList<>();
        var cfg = plugin.getConfigManager().getPlayersConfig();
        if (cfg != null) {
            for (String key : cfg.getKeys(false)) {
                try { out.add(UUID.fromString(key)); } catch (IllegalArgumentException ignored) {}
            }
        }
        // Sicherheitshalber auch alle aktuell online Spieler aufnehmen, falls
        // jemand noch nie gespeichert wurde.
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            if (!out.contains(p.getUniqueId())) out.add(p.getUniqueId());
        }
        return out;
    }
}
