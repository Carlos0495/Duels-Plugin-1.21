package dev.duels.commands;

import dev.duels.DuelsPlugin;
import dev.duels.managers.ConfigManager;
import dev.duels.objects.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class StatsCommand implements CommandExecutor {

    private final DuelsPlugin plugin;

    public StatsCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().prefixed("general.players-only", "&cOnly players can use this command!"));
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            showPlayerStats(player, player.getUniqueId());
        } else {
            // Stats von anderem Spieler anzeigen
            Player target = Bukkit.getPlayer(args[0]);
            if (target != null) {
                showPlayerStats(player, target.getUniqueId());
            } else {
                // Spieler offline, versuchen aus Config zu laden
                UUID targetUUID = plugin.getPlayerManager().getUUIDFromName(args[0]);
                if (targetUUID != null) {
                    showPlayerStats(player, targetUUID);
                } else {
                    player.sendMessage(plugin.getConfigManager().prefixed("stats.player-not-found", "&cPlayer not found!"));
                }
            }
        }

        return true;
    }

    private void showPlayerStats(Player viewer, UUID targetUUID) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(targetUUID);

        int kills = data.getKills();
        int deaths = data.getDeaths();
        int wins = data.getWins();
        int losses = data.getLosses();
        double kd = data.getKD();
        double winrate = data.getWinRate();

        String playerName = data.getName();
        if (playerName == null || playerName.equals("Unknown")) {
            Player online = Bukkit.getPlayer(targetUUID);
            if (online != null) {
                playerName = online.getName();
            }
        }

        viewer.sendMessage(plugin.getConfigManager().prefixed("stats.header", "&bStats &7for &a{player}", java.util.Map.of("player", playerName)));
        ConfigManager cm = plugin.getConfigManager();
        viewer.sendMessage(cm.getMessage("stats.kills", "&2\uD83D\uDDE1 &7ᴋɪʟʟѕ &2{value}", java.util.Map.of("value", String.valueOf(kills))));
        viewer.sendMessage(cm.getMessage("stats.deaths", "&c☠ &7ᴅᴇᴀᴛʜѕ &c{value}", java.util.Map.of("value", String.valueOf(deaths))));
        viewer.sendMessage(cm.getMessage("stats.kd", "&e❤ &7ᴋᴅ &e{value}", java.util.Map.of("value", String.format("%.2f", kd))));
        viewer.sendMessage(cm.getMessage("stats.wins", "&a✔ &7ᴡɪɴѕ &a{value}", java.util.Map.of("value", String.valueOf(wins))));
        viewer.sendMessage(cm.getMessage("stats.losses", "&c✘ &7ʟᴏѕѕᴇѕ &c{value}", java.util.Map.of("value", String.valueOf(losses))));
        viewer.sendMessage(cm.getMessage("stats.winrate", "&b🧪 &7ᴡɪɴ ʀᴀᴛᴇ &b{value}", java.util.Map.of("value", String.format("%.1f%%", winrate))));
    }
}