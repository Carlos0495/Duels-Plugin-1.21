package dev.duels.objects;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory Party. Wird nicht persistiert — Parties gelten nur für die aktuelle
 * Session/Server-Uptime.
 *
 * <p>Der Leader ist immer Teil von {@link #members}.</p>
 *
 * <p>Team-Zuordnung: 0 = kein Team (FFA/neutral), 1 = Team 1, 2 = Team 2.</p>
 */
public class Party {

    private UUID leader;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Set<UUID> invites = new LinkedHashSet<>();
    private final Map<UUID, Integer> teams = new LinkedHashMap<>();
    private boolean isPublic;
    private final long createdAt;

    public Party(UUID leader) {
        this.leader = leader;
        this.members.add(leader);
        this.teams.put(leader, 0);
        this.isPublic = false;
        this.createdAt = System.currentTimeMillis();
    }

    public UUID getLeader() { return leader; }
    public void setLeader(UUID leader) {
        this.leader = leader;
        if (!members.contains(leader)) members.add(leader);
    }

    public Set<UUID> getMembers() { return members; }
    public Set<UUID> getInvites() { return invites; }

    public boolean isMember(UUID uuid) { return members.contains(uuid); }
    public boolean isInvited(UUID uuid) { return invites.contains(uuid); }

    public void addMember(UUID uuid) {
        members.add(uuid);
        teams.putIfAbsent(uuid, 0);
        invites.remove(uuid);
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
        teams.remove(uuid);
    }

    public void addInvite(UUID uuid) { invites.add(uuid); }
    public void removeInvite(UUID uuid) { invites.remove(uuid); }

    public int size() { return members.size(); }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean aPublic) { isPublic = aPublic; }

    public long getCreatedAt() { return createdAt; }

    // Team handling
    public int getTeam(UUID uuid) { return teams.getOrDefault(uuid, 0); }
    public void setTeam(UUID uuid, int team) {
        if (team < 0 || team > 2) team = 0;
        if (!members.contains(uuid)) return;
        teams.put(uuid, team);
    }
    public Map<UUID, Integer> getTeams() { return teams; }
    public void resetTeams() {
        for (UUID u : members) teams.put(u, 0);
    }
}
