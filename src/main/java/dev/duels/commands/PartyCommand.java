package dev.duels.commands;

import dev.duels.DuelsPlugin;
import dev.duels.managers.ConfigManager;
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
            sender.sendMessage(plugin.getConfigManager().prefixed("general.players-only", "&cOnly players can use this command."));
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
                    player.sendMessage(plugin.getConfigManager().prefixed("party.already-in", "&cYou are already in a party."));
                    return true;
                }
                Party party = pm.createParty(player);
                plugin.getHotbarManager().applyMode(player, HotbarManager.MODE_PARTY_LEADER);
                player.sendMessage(plugin.getConfigManager().prefixed("party.opened-alt", "&dParty &7opened! Use &e/party invite <player>&7 or the hotbar."));
            }
            case "invite" -> {
                if (args.length < 2) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.usage-invite", "&cUsage: /party invite <player>"));
                    return true;
                }
                Party party = pm.getPartyByLeader(player.getUniqueId());
                if (party == null && pm.isInParty(player.getUniqueId())) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.only-leader-invite", "&cOnly the leader can invite."));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.player-not-online", "&cPlayer not online: {player}", java.util.Map.of("player", args[1])));
                    return true;
                }
                if (target.equals(player)) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.cannot-invite-self", "&cYou can't invite yourself."));
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
                    player.sendMessage(plugin.getConfigManager().prefixed("party.not-in-party", "&cYou are not in a party."));
                    return true;
                }
                pm.leaveParty(player);
            }
            case "disband" -> {
                Party party = pm.getPartyByLeader(player.getUniqueId());
                if (party == null) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.not-leader", "&cYou are not a party leader."));
                    return true;
                }
                pm.disband(party);
            }
            case "kick" -> {
                Party party = pm.getPartyByLeader(player.getUniqueId());
                if (party == null) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.only-leader-kick", "&cOnly the leader can kick."));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.usage-kick", "&cUsage: /party kick <player>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                UUID targetId = target != null ? target.getUniqueId()
                        : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
                if (!pm.kickMember(player, targetId)) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.could-not-kick", "&cCould not kick {player}.", java.util.Map.of("player", args[1])));
                }
            }
            case "public" -> {
                pm.togglePublic(player);
            }
            case "join" -> {
                if (args.length < 2) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.usage-join", "&cUsage: /party join <leader>"));
                    return true;
                }
                pm.joinPublicByName(player, args[1]);
            }
            case "list" -> {
                var list = pm.getPublicParties();
                if (list.isEmpty()) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.no-public-parties", "&7No public parties currently."));
                    return true;
                }
                player.sendMessage(plugin.getConfigManager().prefixed("party.public-list-header", "&dPublic parties:"));
                for (Party p : list) {
                    Player leader = Bukkit.getPlayer(p.getLeader());
                    String leaderName = leader != null ? leader.getName() : p.getLeader().toString();
                    player.sendMessage(plugin.getConfigManager().getMessage("party.public-list-entry", "&7 - &f{leader} &7({size} players)  &8[/party join {leader}]", java.util.Map.of("leader", leaderName, "size", String.valueOf(p.size()))));
                }
            }
            case "info" -> {
                Party party = pm.getPartyOf(player.getUniqueId());
                if (party == null) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.not-in-party", "&cYou are not in a party."));
                    return true;
                }
                Player leader = Bukkit.getPlayer(party.getLeader());
                player.sendMessage(plugin.getConfigManager().prefixed("party.info-header", "&dParty:"));
                player.sendMessage(plugin.getConfigManager().getMessage("party.info-leader", "&7Leader: &f{leader}", java.util.Map.of("leader", String.valueOf(leader != null ? leader.getName() : party.getLeader()))));
                player.sendMessage(plugin.getConfigManager().getMessage("party.info-public", "&7Public: {value}", java.util.Map.of("value", party.isPublic() ? "&aYes" : "&cNo")));
                player.sendMessage(plugin.getConfigManager().getMessage("party.info-members", "&7Members (&f{size}&7/&f{max}&7):", java.util.Map.of("size", String.valueOf(party.size()), "max", String.valueOf(pm.getMaxSize(leader != null ? leader : player)))));
                for (UUID m : party.getMembers()) {
                    Player mp = Bukkit.getPlayer(m);
                    int team = party.getTeam(m);
                    String teamStr = team == 1 ? " §9[Team 1]" : team == 2 ? " §c[Team 2]" : "";
                    player.sendMessage(plugin.getConfigManager().getMessage("party.info-member-entry", "&7 - &f{player}{team}", java.util.Map.of("player", String.valueOf(mp != null ? mp.getName() : Bukkit.getOfflinePlayer(m).getName()), "team", teamStr)));
                }
            }
            case "menu" -> {
                Party party = pm.getPartyByLeader(player.getUniqueId());
                if (party == null) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.only-leader-menu", "&cOnly the leader can open the menu."));
                    return true;
                }
                plugin.getGuiManager().openPartyMenu(player);
            }
            default -> sendHelp(player);
        }
        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getConfigManager().prefixed("party.help-header", "&dParty Commands:"));
        ConfigManager cm = plugin.getConfigManager();
        player.sendMessage(cm.getMessage("party.help-create", "&7  /party create &8- &7Create a new party"));
        player.sendMessage(cm.getMessage("party.help-invite", "&7  /party invite <player> &8- &7Invite a player"));
        player.sendMessage(cm.getMessage("party.help-accept", "&7  /party accept [leader] &8- &7Accept an invite"));
        player.sendMessage(cm.getMessage("party.help-deny", "&7  /party deny [leader] &8- &7Deny an invite"));
        player.sendMessage(cm.getMessage("party.help-leave", "&7  /party leave &8- &7Leave your party"));
        player.sendMessage(cm.getMessage("party.help-kick", "&7  /party kick <player> &8- &7Kick a member (leader)"));
        player.sendMessage(cm.getMessage("party.help-disband", "&7  /party disband &8- &7Disband your party (leader)"));
        player.sendMessage(cm.getMessage("party.help-public", "&7  /party public &8- &7Toggle public party"));
        player.sendMessage(cm.getMessage("party.help-join", "&7  /party join <leader> &8- &7Join a public party"));
        player.sendMessage(cm.getMessage("party.help-list", "&7  /party list &8- &7List public parties"));
        player.sendMessage(cm.getMessage("party.help-info", "&7  /party info &8- &7Show current party"));
        player.sendMessage(cm.getMessage("party.help-menu", "&7  /party menu &8- &7Open duel menu (leader)"));
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
