package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Add-Variante von {@link SetStatsCommand}: addiert den Betrag zum
 * existierenden Stat-Wert anstatt ihn zu überschreiben. Befehle:
 *   /winsadd /lossesadd /killsadd /deathsadd /coinsadd
 *
 * Unterstützt "all" als Ziel (alle Spieler in players.yml — online +
 * offline). Negative Werte sind erlaubt; PlayerData.setStat klemmt
 * negative Resultate auf 0 (keine Minus-Coins/Kills).
 */
public class AddStatsCommand implements CommandExecutor {

    private final DuelsPlugin plugin;

    public AddStatsCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("duels.admin")) {
            sender.sendMessage(plugin.getConfigManager().prefixed("general.no-permission", "&cYou don't have permission!"));
            return true;
        }

        if (args.length != 2) {
            sender.sendMessage(plugin.getConfigManager().prefixed("admin.stats-usage", "&7Usage: &c/{label} <player|all> <amount>", java.util.Map.of("label", label)));
            return true;
        }

        String targetArg = args[0];
        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().prefixed("admin.invalid-amount", "&cInvalid amount!"));
            return true;
        }

        // Befehl-Name → Stat-Typ: "winsadd" → "wins", "coinsadd" → "coins".
        String statType = command.getName().toLowerCase().replace("add", "");

        if (targetArg.equalsIgnoreCase("all")) {
            List<UUID> targets = collectAllKnownPlayerUUIDs();
            if (targets.isEmpty()) {
                sender.sendMessage(plugin.getConfigManager().prefixed("admin.no-players-file", "&cNo players found in players.yml!"));
                return true;
            }
            int n = 0;
            for (UUID uuid : targets) {
                try {
                    plugin.getPlayerManager().addStat(uuid, statType, amount);
                    org.bukkit.entity.Player online = Bukkit.getPlayer(uuid);
                    if (online != null) plugin.getScoreboardManager().updateScoreboard(online);
                    n++;
                } catch (Throwable ignored) {}
            }
            sender.sendMessage(plugin.getConfigManager().prefixed("admin.stats-add-all",
                    "&a{stat} &7+&e{amount} &7added to &e{count} &7player(s) &8(online + offline).",
                    java.util.Map.of("stat", capitalize(statType), "amount", String.valueOf(amount), "count", String.valueOf(n))));
            return true;
        }

        UUID targetUUID = plugin.getPlayerManager().getUUIDFromName(targetArg);
        if (targetUUID == null) {
            try {
                @SuppressWarnings("deprecation")
                org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(targetArg);
                if (offline.getUniqueId() != null) targetUUID = offline.getUniqueId();
            } catch (Throwable ignored) {}
        }
        if (targetUUID == null) {
            sender.sendMessage(plugin.getConfigManager().prefixed("stats.player-not-found", "&cPlayer not found!"));
            return true;
        }

        plugin.getPlayerManager().addStat(targetUUID, statType, amount);

        sender.sendMessage(plugin.getConfigManager().prefixed("admin.stats-add",
                "&a{stat} &7of &e{player} &7+&e{amount}&7.",
                java.util.Map.of("stat", capitalize(statType), "player", targetArg, "amount", String.valueOf(amount))));

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

    private List<UUID> collectAllKnownPlayerUUIDs() {
        List<UUID> out = new ArrayList<>();
        var cfg = plugin.getConfigManager().getPlayersConfig();
        if (cfg != null) {
            for (String key : cfg.getKeys(false)) {
                try { out.add(UUID.fromString(key)); } catch (IllegalArgumentException ignored) {}
            }
        }
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            if (!out.contains(p.getUniqueId())) out.add(p.getUniqueId());
        }
        return out;
    }
}
