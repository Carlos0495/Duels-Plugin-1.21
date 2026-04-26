package dev.duels.managers;

import dev.duels.DuelsPlugin;
import dev.duels.objects.Party;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Verwaltet alle aktiven Parties (nicht persistiert). Unterstützt Einladungen,
 * Public-Parties und Permission-basierte Größenlimits:
 * <ul>
 *     <li>{@code duels.party.size.15} – 15 (Default)</li>
 *     <li>{@code duels.party.size.20} – 20</li>
 *     <li>{@code duels.party.size.30} – 30</li>
 *     <li>{@code duels.party.public} – darf Public-Parties eröffnen</li>
 * </ul>
 * Limits können in {@code config.yml} unter {@code party.size.*} überschrieben werden.
 */
public class PartyManager {

    public static final String PERM_PUBLIC = "duels.party.public";

    private final DuelsPlugin plugin;
    private final Map<UUID, Party> parties = new HashMap<>(); // leader UUID -> party
    private final Map<UUID, UUID> memberToLeader = new HashMap<>();
    // pending invites: invited player -> set of leader UUIDs
    private final Map<UUID, Set<UUID>> pendingInvites = new HashMap<>();

    public PartyManager(DuelsPlugin plugin) {
        this.plugin = plugin;
        // Defaults
        var main = plugin.getConfigManager().getMainConfig();
        boolean dirty = false;
        if (!main.contains("party.size.default")) { main.set("party.size.default", 15); dirty = true; }
        if (!main.contains("party.size.tier1")) { main.set("party.size.tier1", 20); dirty = true; }
        if (!main.contains("party.size.tier2")) { main.set("party.size.tier2", 30); dirty = true; }
        if (!main.contains("party.announce-message"))
            { main.set("party.announce-message", "&d[Party] &f%leader% &7opened a &epublic party&7! Click to join."); dirty = true; }
        if (!main.contains("party.invite-timeout-seconds"))
            { main.set("party.invite-timeout-seconds", 60); dirty = true; }
        if (dirty) plugin.saveConfig();
    }

    // -------- Lookup helpers --------

    public Party getPartyByLeader(UUID leader) {
        return parties.get(leader);
    }

    public Party getPartyOf(UUID member) {
        UUID leader = memberToLeader.get(member);
        if (leader == null) return null;
        return parties.get(leader);
    }

    public boolean isInParty(UUID uuid) {
        return memberToLeader.containsKey(uuid);
    }

    public boolean isLeader(UUID uuid) {
        Party p = parties.get(uuid);
        return p != null && p.getLeader().equals(uuid);
    }

    public int getMaxSize(Player player) {
        var cfg = plugin.getConfigManager().getMainConfig();
        int def = cfg.getInt("party.size.default", 15);
        int tier1 = cfg.getInt("party.size.tier1", 20);
        int tier2 = cfg.getInt("party.size.tier2", 30);

        int max = def;
        if (player.hasPermission("duels.party.size.20")) max = Math.max(max, tier1);
        if (player.hasPermission("duels.party.size.30")) max = Math.max(max, tier2);
        return max;
    }

    public List<Party> getPublicParties() {
        List<Party> list = new ArrayList<>();
        for (Party p : parties.values()) {
            if (p.isPublic()) list.add(p);
        }
        return list;
    }

    // -------- Party lifecycle --------

    public Party createParty(Player leader) {
        UUID uuid = leader.getUniqueId();
        if (isInParty(uuid)) return parties.get(memberToLeader.get(uuid));

        Party party = new Party(uuid);
        parties.put(uuid, party);
        memberToLeader.put(uuid, uuid);
        return party;
    }

    public void disband(Party party) {
        if (party == null) return;
        Set<UUID> members = new HashSet<>(party.getMembers());
        for (UUID m : members) {
            memberToLeader.remove(m);
            Player p = Bukkit.getPlayer(m);
            if (p != null && p.isOnline()) {
                if (m.equals(party.getLeader())) {
                    p.sendMessage(plugin.getPrefix() + "§cYou disbanded your party.");
                } else {
                    p.sendMessage(plugin.getPrefix() + "§cThe party was disbanded.");
                }
                // Switch back to lobby hotbar
                plugin.getHotbarManager().applyMode(p, HotbarManager.MODE_LOBBY);
            }
        }
        // Clear out any pending invites referencing this party
        UUID leader = party.getLeader();
        for (Set<UUID> set : pendingInvites.values()) set.remove(leader);
        parties.remove(leader);
    }

    public boolean leaveParty(Player player) {
        UUID uuid = player.getUniqueId();
        UUID leaderId = memberToLeader.remove(uuid);
        if (leaderId == null) return false;

        Party party = parties.get(leaderId);
        if (party == null) return false;

        if (party.getLeader().equals(uuid)) {
            // Leader leaves -> disband
            // Put back before disband so other members get cleaned up too
            memberToLeader.put(uuid, leaderId);
            disband(party);
            return true;
        }

        party.removeMember(uuid);
        player.sendMessage(plugin.getPrefix() + "§7You left the party.");
        plugin.getHotbarManager().applyMode(player, HotbarManager.MODE_LOBBY);

        broadcast(party, "§7" + player.getName() + " §eleft the party.");
        return true;
    }

    public boolean kickMember(Player leader, UUID target) {
        Party party = parties.get(leader.getUniqueId());
        if (party == null) return false;
        if (!party.getMembers().contains(target)) return false;
        if (target.equals(leader.getUniqueId())) return false;

        party.removeMember(target);
        memberToLeader.remove(target);

        Player t = Bukkit.getPlayer(target);
        if (t != null && t.isOnline()) {
            t.sendMessage(plugin.getPrefix() + "§cYou were kicked from the party.");
            plugin.getHotbarManager().applyMode(t, HotbarManager.MODE_LOBBY);
        }

        broadcast(party, "§7" + (t != null ? t.getName() : target.toString()) + " §cwas kicked.");
        return true;
    }

    public boolean invite(Player leader, Player target) {
        Party party = parties.get(leader.getUniqueId());
        if (party == null) {
            party = createParty(leader);
            // Give leader hotbar
            plugin.getHotbarManager().applyMode(leader, HotbarManager.MODE_PARTY_LEADER);
            leader.sendMessage(plugin.getPrefix() + "§dParty §7opened. Use §e/party invite <player>§7 or the hotbar items.");
        }

        if (party.getMembers().contains(target.getUniqueId())) {
            leader.sendMessage(plugin.getPrefix() + "§c" + target.getName() + " is already in your party.");
            return false;
        }

        if (party.size() >= getMaxSize(leader)) {
            leader.sendMessage(plugin.getPrefix() + "§cYour party is full (max " + getMaxSize(leader) + ").");
            return false;
        }

        party.addInvite(target.getUniqueId());
        pendingInvites.computeIfAbsent(target.getUniqueId(), k -> new HashSet<>()).add(leader.getUniqueId());

        Component accept = Component.text("[Accept]").color(NamedTextColor.GREEN)
                .hoverEvent(HoverEvent.showText(Component.text("/party accept " + leader.getName())))
                .clickEvent(ClickEvent.runCommand("/party accept " + leader.getName()));
        Component deny = Component.text("[Deny]").color(NamedTextColor.RED)
                .hoverEvent(HoverEvent.showText(Component.text("/party deny " + leader.getName())))
                .clickEvent(ClickEvent.runCommand("/party deny " + leader.getName()));

        target.sendMessage(plugin.getPrefix() + "§d" + leader.getName() + " §7invited you to their party.");
        target.sendMessage(Component.text(plugin.getPrefix()).append(accept).append(Component.text("  ")).append(deny));

        leader.sendMessage(plugin.getPrefix() + "§7Invite sent to §f" + target.getName() + "§7.");

        int timeoutSec = plugin.getConfigManager().getMainConfig().getInt("party.invite-timeout-seconds", 60);
        UUID targetId = target.getUniqueId();
        UUID leaderId = leader.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> expireInvite(leaderId, targetId), timeoutSec * 20L);
        return true;
    }

    private void expireInvite(UUID leaderId, UUID targetId) {
        Party party = parties.get(leaderId);
        if (party == null) return;
        if (!party.isInvited(targetId)) return;
        party.removeInvite(targetId);
        Set<UUID> set = pendingInvites.get(targetId);
        if (set != null) set.remove(leaderId);

        Player leader = Bukkit.getPlayer(leaderId);
        Player target = Bukkit.getPlayer(targetId);
        if (leader != null && leader.isOnline() && target != null) {
            leader.sendMessage(plugin.getPrefix() + "§7Invite to §f" + target.getName() + " §cexpired.");
        }
    }

    public boolean acceptInvite(Player target, String leaderName) {
        Player leader = (leaderName == null || leaderName.isEmpty()) ? null : Bukkit.getPlayer(leaderName);
        UUID leaderId = leader != null ? leader.getUniqueId() : null;

        Set<UUID> invitingLeaders = pendingInvites.get(target.getUniqueId());
        if (invitingLeaders == null || invitingLeaders.isEmpty()) {
            target.sendMessage(plugin.getPrefix() + "§cYou have no pending invites.");
            return false;
        }

        if (leaderId == null) {
            // If exactly one invite, auto-pick
            if (invitingLeaders.size() == 1) {
                leaderId = invitingLeaders.iterator().next();
            } else {
                target.sendMessage(plugin.getPrefix() + "§cSpecify whose invite: /party accept <player>");
                return false;
            }
        }

        if (!invitingLeaders.contains(leaderId)) {
            target.sendMessage(plugin.getPrefix() + "§cNo invite from that player.");
            return false;
        }

        Party party = parties.get(leaderId);
        if (party == null) {
            invitingLeaders.remove(leaderId);
            target.sendMessage(plugin.getPrefix() + "§cThat party no longer exists.");
            return false;
        }

        // Check if player is already in another party
        if (isInParty(target.getUniqueId())) {
            target.sendMessage(plugin.getPrefix() + "§cYou are already in a party. Leave it first.");
            return false;
        }

        Player leaderPlayer = Bukkit.getPlayer(leaderId);
        if (party.size() >= (leaderPlayer != null ? getMaxSize(leaderPlayer) : 15)) {
            target.sendMessage(plugin.getPrefix() + "§cThat party is full.");
            return false;
        }

        party.addMember(target.getUniqueId());
        memberToLeader.put(target.getUniqueId(), leaderId);
        invitingLeaders.remove(leaderId);

        // Switch hotbar
        plugin.getHotbarManager().applyMode(target, HotbarManager.MODE_PARTY_MEMBER);

        broadcast(party, "§a" + target.getName() + " §7joined the party!");
        return true;
    }

    public boolean denyInvite(Player target, String leaderName) {
        Player leader = (leaderName == null || leaderName.isEmpty()) ? null : Bukkit.getPlayer(leaderName);
        UUID leaderId = leader != null ? leader.getUniqueId() : null;

        Set<UUID> invitingLeaders = pendingInvites.get(target.getUniqueId());
        if (invitingLeaders == null || invitingLeaders.isEmpty()) {
            target.sendMessage(plugin.getPrefix() + "§cNo pending invites.");
            return false;
        }

        if (leaderId == null && invitingLeaders.size() == 1) {
            leaderId = invitingLeaders.iterator().next();
        }
        if (leaderId == null || !invitingLeaders.contains(leaderId)) {
            target.sendMessage(plugin.getPrefix() + "§cNo invite from that player.");
            return false;
        }

        Party party = parties.get(leaderId);
        if (party != null) party.removeInvite(target.getUniqueId());
        invitingLeaders.remove(leaderId);

        target.sendMessage(plugin.getPrefix() + "§7Invite denied.");
        Player lp = Bukkit.getPlayer(leaderId);
        if (lp != null) lp.sendMessage(plugin.getPrefix() + "§c" + target.getName() + " denied your invite.");
        return true;
    }

    public boolean joinPublic(Player joiner, Player leader) {
        Party party = parties.get(leader.getUniqueId());
        if (party == null || !party.isPublic()) {
            joiner.sendMessage(plugin.getPrefix() + "§cThat party is not public.");
            return false;
        }
        if (isInParty(joiner.getUniqueId())) {
            joiner.sendMessage(plugin.getPrefix() + "§cYou are already in a party.");
            return false;
        }
        if (party.size() >= getMaxSize(leader)) {
            joiner.sendMessage(plugin.getPrefix() + "§cThat party is full.");
            return false;
        }

        party.addMember(joiner.getUniqueId());
        memberToLeader.put(joiner.getUniqueId(), leader.getUniqueId());
        plugin.getHotbarManager().applyMode(joiner, HotbarManager.MODE_PARTY_MEMBER);
        broadcast(party, "§a" + joiner.getName() + " §7joined the public party!");
        return true;
    }

    public boolean togglePublic(Player leader) {
        if (!leader.hasPermission(PERM_PUBLIC)) {
            leader.sendMessage(plugin.getPrefix() + "§cYou don't have permission for public parties.");
            return false;
        }
        Party party = parties.get(leader.getUniqueId());
        if (party == null) {
            leader.sendMessage(plugin.getPrefix() + "§cYou don't have a party. Use /party create first.");
            return false;
        }
        party.setPublic(!party.isPublic());
        if (party.isPublic()) {
            announceInChat(party, leader);
            leader.sendMessage(plugin.getPrefix() + "§7Party is now §apublic§7.");
        } else {
            leader.sendMessage(plugin.getPrefix() + "§7Party is now §cprivate§7.");
        }
        return true;
    }

    public void announceInChat(Party party, Player leader) {
        String raw = plugin.getConfigManager().getMainConfig()
                .getString("party.announce-message",
                        "&d[Party] &f%leader% &7opened a &epublic party&7! Click to join.");
        String processed = raw.replace("%leader%", leader.getName()).replace('&', '§');

        Component msg = Component.text(processed)
                .hoverEvent(HoverEvent.showText(Component.text("/party join " + leader.getName())))
                .clickEvent(ClickEvent.runCommand("/party join " + leader.getName()));

        Bukkit.getServer().sendMessage(msg);
    }

    public void broadcast(Party party, String message) {
        String prefixed = plugin.getPrefix() + message;
        for (UUID m : party.getMembers()) {
            Player p = Bukkit.getPlayer(m);
            if (p != null && p.isOnline()) p.sendMessage(prefixed);
        }
    }

    public void handlePlayerQuit(UUID uuid) {
        // Clear pending invites for this player
        pendingInvites.remove(uuid);

        // Remove invites others sent them
        for (Party p : parties.values()) p.removeInvite(uuid);

        // If in party, leave
        Party party = getPartyOf(uuid);
        if (party == null) return;

        memberToLeader.remove(uuid);
        if (party.getLeader().equals(uuid)) {
            // Leader left -> transfer leadership to next member, or disband
            List<UUID> rest = new ArrayList<>(party.getMembers());
            rest.remove(uuid);
            party.removeMember(uuid);
            if (rest.isEmpty()) {
                parties.remove(uuid);
                return;
            }
            UUID newLeaderId = rest.get(0);
            // move party under new leader key
            Party moved = new Party(newLeaderId);
            for (UUID m : rest) {
                if (!m.equals(newLeaderId)) moved.addMember(m);
                memberToLeader.put(m, newLeaderId);
            }
            moved.setPublic(party.isPublic());
            parties.remove(uuid);
            parties.put(newLeaderId, moved);
            Player np = Bukkit.getPlayer(newLeaderId);
            if (np != null && np.isOnline()) {
                np.sendMessage(plugin.getPrefix() + "§dYou are now the §fparty leader§d.");
                plugin.getHotbarManager().applyMode(np, HotbarManager.MODE_PARTY_LEADER);
            }
            broadcast(moved, "§7Leader left. §d" + (np != null ? np.getName() : newLeaderId.toString()) + " §7is now the leader.");
        } else {
            party.removeMember(uuid);
            broadcast(party, "§7" + Bukkit.getOfflinePlayer(uuid).getName() + " §edisconnected.");
        }
    }

    public void cleanupAll() {
        parties.clear();
        memberToLeader.clear();
        pendingInvites.clear();
    }

    public Map<UUID, Party> getAllParties() {
        return Collections.unmodifiableMap(parties);
    }

    /** Auto-accept: join a public party by leader's UUID (used by /party join). */
    public boolean joinPublicByName(Player joiner, String leaderName) {
        Player leader = Bukkit.getPlayer(leaderName);
        if (leader == null) {
            joiner.sendMessage(plugin.getPrefix() + "§cPlayer not found: " + leaderName);
            return false;
        }
        return joinPublic(joiner, leader);
    }
}
