package dev.duels.commands;

import dev.duels.DuelsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class FlyCommand implements CommandExecutor {

    private final DuelsPlugin plugin;
    private final java.util.Map<java.util.UUID, Long> flyCooldown = new java.util.HashMap<>();

    public FlyCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        dev.duels.managers.ConfigManager cm = plugin.getConfigManager();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(cm.prefixed("general.players-only", "Only players can use this command!"));
            return true;
        }

        // /fly disable <player|all> — Admin-Befehl
        if (args.length >= 2 && args[0].equalsIgnoreCase("disable")) {
            if (!player.hasPermission("duels.admin")) {
                player.sendMessage(cm.prefixed("general.no-permission", "&cNo permission."));
                return true;
            }
            String targetArg = args[1];
            if (targetArg.equalsIgnoreCase("all")) {
                int count = 0;
                for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (p.getAllowFlight() || p.isFlying()) {
                        p.setFlying(false);
                        p.setAllowFlight(false);
                        plugin.getPlayerManager().setAutoFly(p.getUniqueId(), false);
                        p.sendMessage(cm.prefixed("fly.disabled-by-admin", "&7Fly: &cOFF &8(disabled by admin)"));
                        count++;
                    }
                }
                player.sendMessage(cm.prefixed("fly.disabled-others", "&7Disabled fly for &f{count} &7players.",
                        java.util.Map.of("count", String.valueOf(count))));
            } else {
                Player target = org.bukkit.Bukkit.getPlayerExact(targetArg);
                if (target != null) {
                    // Online-Spieler
                    target.setFlying(false);
                    target.setAllowFlight(false);
                    plugin.getPlayerManager().setAutoFly(target.getUniqueId(), false);
                    target.sendMessage(cm.prefixed("fly.disabled-by-admin", "&7Fly: &cOFF &8(disabled by admin)"));
                    player.sendMessage(cm.prefixed("fly.disabled-other", "&7Disabled fly for &f{player}&7.",
                            java.util.Map.of("player", target.getName())));
                } else {
                    // Offline-Spieler: UUID auflösen und persistent setzen.
                    @SuppressWarnings("deprecation")
                    org.bukkit.OfflinePlayer off = org.bukkit.Bukkit.getOfflinePlayer(targetArg);
                    if (off == null || (!off.hasPlayedBefore()
                            && !plugin.getConfigManager().getPlayersConfig()
                                    .contains(off.getUniqueId().toString()))) {
                        player.sendMessage(cm.prefixed("general.player-not-found", "&cPlayer not found: &f{player}",
                                java.util.Map.of("player", targetArg)));
                        return true;
                    }
                    plugin.getPlayerManager().setAutoFlyPersistent(off.getUniqueId(), false);
                    player.sendMessage(cm.prefixed("fly.disabled-other-offline",
                            "&7Disabled fly for &f{player} &8(offline, applies on rejoin).",
                            java.util.Map.of("player", off.getName() != null ? off.getName() : targetArg)));
                }
            }
            return true;
        }

        if (!player.hasPermission("duels.fly")) {
            player.sendMessage(cm.prefixed("fly.no-permission", "&cYou don't have permission!"));
            return true;
        }

        if (!plugin.getPlayerManager().isInLobby(player)) {
            player.sendMessage(cm.prefixed("fly.only-lobby", "&cYou can only use /fly in the lobby!"));
            return true;
        }

        boolean currentlyFlying = player.isFlying() || player.getAllowFlight();

        if (currentlyFlying) {
            player.setFlying(false);
            player.setAllowFlight(false);
            plugin.getPlayerManager().setAutoFly(player.getUniqueId(), false);
            player.sendMessage(cm.prefixed("fly.off", "&7Fly: &cOFF &8(autofly disabled)"));
        } else {
            // Cooldown prüfen
            int cooldownSec = plugin.getConfigManager().getMainConfig()
                    .getInt("party.fly-cooldown", 3);
            long now = System.currentTimeMillis();
            Long last = flyCooldown.get(player.getUniqueId());
            if (last != null && (now - last) < cooldownSec * 1000L) {
                int remaining = (int) ((cooldownSec * 1000L - (now - last)) / 1000) + 1;
                player.sendMessage(cm.prefixed("fly.cooldown", "&cPlease wait &f{seconds}s &cbefore enabling fly again.",
                        java.util.Map.of("seconds", String.valueOf(remaining))));
                return true;
            }
            flyCooldown.put(player.getUniqueId(), now);

            player.setAllowFlight(true);
            player.setFlying(true);
            plugin.getPlayerManager().setAutoFly(player.getUniqueId(), true);
            player.sendMessage(cm.prefixed("fly.on", "&7Fly: &aON &8(autofly enabled)"));
        }

        return true;
    }
}