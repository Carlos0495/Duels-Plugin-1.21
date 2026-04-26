package dev.duels.commands;

import dev.duels.DuelsPlugin;
import dev.duels.managers.HotbarManager;
import dev.duels.objects.Party;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PartyCommand implements CommandExecutor, TabCompleter {

    private final DuelsPlugin plugin;

    public PartyCommand(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getPrefix() + "§cOnly players can use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        var pm = plugin.getPartyManager();

        switch (sub) {
            case "help" -> sendHelp(player);
            case "create", "open" -> {
                if (pm.isInParty(player.getUniqueId())) {
                    player.sendMessage(plugin.getPrefix() + "§cYou are already in a party.");
                    return true;
                }
                Party party = pm.createParty(player);
                plugin.getHotbarManager().applyMode(player, HotbarManager.MODE_PARTY_LEADER);
                player.sendMessage(plugin.getPrefix() + "§dParty §7opened! Use §e/party invite <player>§7 or the hotbar.");
            }
            case "invite" -> {
                if (args.length < 2) {
                    player.sendMessage(plugin.getPrefix() + "§cUsage: /party invite <player>");
                    return true;
                }
                Party party = pm.getPartyByLeader(player.getUniqueId());
                if (party == null && pm.isInParty(player.getUniqueId())) {
                    player.sendMessage(plugin.getPrefix() + "§cOnly the leader can invite.");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    player.sendMessage(plugin.getPrefix() + "§cPlayer not online: " + args[1]);
                    return true;
                }
                if (target.equals(player)) {
                    player.sendMessage(plugin.getPrefix() + "§cYou can't invite yourself.");
                    return true;
                }
                pm.invite(player, target);
            }
            case "accept" -> {
                String name = args.length >= 2 ? args[1] : null;
                pm.acceptInvite(player, name);
            }
            case "deny", "decline" -> {
                String name = args.length >= 2 ? args[1] : null;
                pm.denyInvite(player, name);
            }
            case "leave" -> {
                if (!pm.isInParty(player.getUniqueId())) {
                    player.sendMessage(plugin.getPrefix() + "§cYou are not in a party.");
                    return true;
                }
                pm.leaveParty(player);
            }
            case "disband" -> {
                Party party = pm.getPartyByLeader(player.getUniqueId());
                if (party == null) {
                    player.sendMessage(plugin.getPrefix() + "§cYou are not a party leader.");
                    return true;
                }
                pm.disband(party);
            }
            case "kick" -> {
                Party party = pm.getPartyByLeader(player.getUniqueId());
                if (party == null) {
                    player.sendMessage(plugin.getPrefix() + "§cOnly the leader can kick.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(plugin.getPrefix() + "§cUsage: /party kick <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                UUID targetId = target != null ? target.getUniqueId()
                        : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
                if (!pm.kickMember(player, targetId)) {
                    player.sendMessage(plugin.getPrefix() + "§cCould not kick " + args[1] + ".");
                }
            }
            case "public" -> {
                pm.togglePublic(player);
            }
            case "join" -> {
                if (args.length < 2) {
                    player.sendMessage(plugin.getPrefix() + "§cUsage: /party join <leader>");
                    return true;
                }
                pm.joinPublicByName(player, args[1]);
            }
            case "list" -> {
                var list = pm.getPublicParties();
                if (list.isEmpty()) {
                    player.sendMessage(plugin.getPrefix() + "§7No public parties currently.");
                    return true;
                }
                player.sendMessage(plugin.getPrefix() + "§dPublic parties:");
                for (Party p : list) {
                    Player leader = Bukkit.getPlayer(p.getLeader());
                    String leaderName = leader != null ? leader.getName() : p.getLeader().toString();
                    player.sendMessage("§7 - §f" + leaderName + " §7(" + p.size() + " players)"
                            + "  §8[/party join " + leaderName + "]");
                }
            }
            case "info" -> {
                Party party = pm.getPartyOf(player.getUniqueId());
                if (party == null) {
                    player.sendMessage(plugin.getPrefix() + "§cYou are not in a party.");
                    return true;
                }
                Player leader = Bukkit.getPlayer(party.getLeader());
                player.sendMessage(plugin.getPrefix() + "§dParty:");
                player.sendMessage("§7Leader: §f" + (leader != null ? leader.getName() : party.getLeader()));
                player.sendMessage("§7Public: " + (party.isPublic() ? "§aYes" : "§cNo"));
                player.sendMessage("§7Members (§f" + party.size() + "§7/§f" + pm.getMaxSize(leader != null ? leader : player) + "§7):");
                for (UUID m : party.getMembers()) {
                    Player mp = Bukkit.getPlayer(m);
                    int team = party.getTeam(m);
                    String teamStr = team == 1 ? " §9[Team 1]" : team == 2 ? " §c[Team 2]" : "";
                    player.sendMessage("§7 - §f" + (mp != null ? mp.getName() : Bukkit.getOfflinePlayer(m).getName()) + teamStr);
                }
            }
            case "menu" -> {
                Party party = pm.getPartyByLeader(player.getUniqueId());
                if (party == null) {
                    player.sendMessage(plugin.getPrefix() + "§cOnly the leader can open the menu.");
                    return true;
                }
                plugin.getGuiManager().openPartyMenu(player);
            }
            default -> sendHelp(player);
        }
        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getPrefix() + "§dParty Commands:");
        player.sendMessage("§7  /party create §8- §7Create a new party");
        player.sendMessage("§7  /party invite <player> §8- §7Invite a player");
        player.sendMessage("§7  /party accept [leader] §8- §7Accept an invite");
        player.sendMessage("§7  /party deny [leader] §8- §7Deny an invite");
        player.sendMessage("§7  /party leave §8- §7Leave your party");
        player.sendMessage("§7  /party kick <player> §8- §7Kick a member (leader)");
        player.sendMessage("§7  /party disband §8- §7Disband your party (leader)");
        player.sendMessage("§7  /party public §8- §7Toggle public party");
        player.sendMessage("§7  /party join <leader> §8- §7Join a public party");
        player.sendMessage("§7  /party list §8- §7List public parties");
        player.sendMessage("§7  /party info §8- §7Show current party");
        player.sendMessage("§7  /party menu §8- §7Open duel menu (leader)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = List.of("create", "invite", "accept", "deny", "leave", "kick",
                    "disband", "public", "join", "list", "info", "menu", "help");
            List<String> out = new ArrayList<>();
            String partial = args[0].toLowerCase(Locale.ROOT);
            for (String s : subs) if (s.startsWith(partial)) out.add(s);
            return out;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("invite") || sub.equals("kick") || sub.equals("join")
                    || sub.equals("accept") || sub.equals("deny")) {
                List<String> out = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                return out;
            }
        }
        return Collections.emptyList();
    }
}
