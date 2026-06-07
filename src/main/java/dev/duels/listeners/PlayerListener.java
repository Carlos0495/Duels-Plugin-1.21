package dev.duels.listeners;

import dev.duels.DuelsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

public class PlayerListener implements Listener {

    private final DuelsPlugin plugin;

    public PlayerListener(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        plugin.getPlayerManager().loadPlayerData(uuid);
        plugin.getPlayerManager().getPlayerData(uuid).setName(player.getName());

        // Permission-basierte Defaults (Fly/Armortrims) durchsetzen.
        plugin.getPlayerManager().enforcePermissionDefaults(player);

        forceLobbyState(player);

        Location spawn = plugin.getArenaManager().getSpawnLocation();
        if (spawn != null) player.teleport(spawn);

        plugin.getPlayerManager().setupPlayerInventory(player);

        plugin.getPlayerManager().updatePlayerVisibility(player);
        plugin.getPlayerManager().handleJoinVisibility(player);
        plugin.getPlayerManager().enforceDuelPrivacyForJoin(player);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getPlayerManager().applyLobbyFly(player);
        }, 2L);

        plugin.getScoreboardManager().updateScoreboard(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        plugin.getQueueManager().leaveAllQueues(uuid);

        if (plugin.getDuelManager().isInDuel(uuid)) {
            plugin.getDuelManager().handlePlayerDisconnect(uuid);
        }

        if (plugin.getPartyManager() != null) {
            plugin.getPartyManager().handlePlayerQuit(uuid);
        }
        if (plugin.getPartyFFAManager() != null) {
            plugin.getPartyFFAManager().handlePlayerQuit(uuid);
        }
        if (plugin.getSpectateManager() != null) {
            plugin.getSpectateManager().handlePlayerQuit(uuid);
        }

        plugin.getScoreboardManager().removeScoreboard(uuid);
        if (plugin.getTeamLabelManager() != null) {
            plugin.getTeamLabelManager().removeLabel(uuid);
        }
        plugin.getPlayerManager().savePlayerData(uuid);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Location pendingRespawn = plugin.getDuelManager().getPendingRespawn(uuid);
        if (pendingRespawn != null && plugin.getDuelManager().isInDuel(uuid)) {
            event.setRespawnLocation(pendingRespawn);
            plugin.getDuelManager().removePendingRespawn(uuid);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (plugin.getDuelManager().isInDuel(uuid)) {
                    forceRoundState(player);
                    String kitName = plugin.getDuelManager().getDuelSession(uuid).getKitName();
                    plugin.getKitManager().giveKit(player, kitName);
                    plugin.getKitManager().applyKitStartEffects(player, kitName);
                }
            }, 1L);
            return;
        }

        Location spawn = plugin.getArenaManager().getSpawnLocation();
        if (spawn != null) event.setRespawnLocation(spawn);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!plugin.getDuelManager().isInDuel(uuid)) {
                forceLobbyState(player);
                plugin.getPlayerManager().setupPlayerInventory(player);
                // updateInventory() erzwingt Client-Server Inventory-Sync
                // und verhindert Ghost-State nach Self-Kill.
                player.updateInventory();
                spawnParticlesCircle(player);
                plugin.getPlayerManager().applyLobbyFly(player);
            }
        }, 2L);
    }

    /**
     * Chat-Filter: isoliert den Chat für Spieler in Matches und für Welten
     * mit eigenem Chat (per-world / Lobby). Wir verändern nur die Empfänger
     * des Events — wir canceln NICHT und überschreiben das Format NICHT,
     * damit Chat-/DeathMessage-Plugins weiter funktionieren.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChatFilter(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        UUID uuid = sender.getUniqueId();
        var cfg = plugin.getConfigManager();

        java.util.Set<UUID> allowed = null; // null = kein Filter

        // 1) Match-Chat-Isolation (höchste Priorität).
        if (cfg.isDuelChatIsolated()) {
            java.util.Set<UUID> peers = plugin.getPlayerManager().getMatchPeers(uuid);
            if (peers != null) {
                allowed = new java.util.HashSet<>(peers);
            }
        }

        // 2) Per-Welt / Lobby isolierter Chat (nur wenn nicht schon Match-Filter).
        if (allowed == null && sender.getWorld() != null) {
            String world = sender.getWorld().getName().toLowerCase();
            boolean perWorld = cfg.getPerWorldChatWorlds().contains(world);
            boolean lobby = cfg.isLobbyChatIsolated()
                    && plugin.getPlayerManager().isInLobbyWorld(sender);
            if (perWorld || lobby) {
                allowed = new java.util.HashSet<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getWorld() != null
                            && p.getWorld().getName().equalsIgnoreCase(sender.getWorld().getName())) {
                        allowed.add(p.getUniqueId());
                    }
                }
            }
        }

        if (allowed == null) return; // kein Filter aktiv

        allowed.add(uuid); // Sender sieht seine eigene Nachricht immer
        final java.util.Set<UUID> finalAllowed = allowed;
        event.getRecipients().removeIf(r -> !finalAllowed.contains(r.getUniqueId()));
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        // Spieler im Duel/FFA: nichts machen, ihre Inventare gehören dem Match.
        if (plugin.getDuelManager().isInDuel(player.getUniqueId())) return;
        if (plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(player.getUniqueId())) return;
        // setupPlayerInventory entscheidet selbst (basierend auf der neuen
        // Welt) ob Hotbar gesetzt oder Inventar geleert wird.
        plugin.getPlayerManager().setupPlayerInventory(player);
        // Per-Welt Tablist-Filter neu berechnen (für alle, da auch andere
        // den Welt-Wechsler ein-/ausblenden müssen).
        plugin.getPlayerManager().refreshAllVisibility();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();

        // Während Spectate KEINE externe Gamemode-Änderung erlauben
        // (Multiverse force-gamemode etc. würde sonst SURVIVAL erzwingen).
        if (plugin.getSpectateManager() != null
                && plugin.getSpectateManager().isSpectating(player.getUniqueId())
                && event.getNewGameMode() != GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }

        if (event.getNewGameMode() == GameMode.SURVIVAL) {
            if (!plugin.getDuelManager().isInDuel(player.getUniqueId())) {
                plugin.getPlayerManager().setupPlayerInventory(player);
            }
        } else if (event.getNewGameMode() == GameMode.CREATIVE) {
            // Beim Wechsel in den Kreativ-Modus NUR die Plugin-Hotbar-Items
            // entfernen (PDC-getaggt). Alles andere im Inventar (Build-
            // Materialien, Tools, persönliche Items) bleibt erhalten —
            // User-Wunsch: "wenn man in kreativ geht, nur die hotbaritems
            // weggehen und sonst egal was man in inventar hat es nicht
            // weggeht".
            if (!plugin.getDuelManager().isInDuel(player.getUniqueId())) {
                plugin.getPlayerManager().removeHotbarItems(player);
                player.updateInventory();
            }
        }
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // Im Duel/FFA/Team-Match: Vanilla-Verhalten (Hunger nimmt ab,
        // essen heilt etc.) — nichts erzwingen.
        if (plugin.getDuelManager().isInDuel(player.getUniqueId())) return;
        if (plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(player.getUniqueId())) return;

        // In der Lobby-Welt: Hunger bleibt voll (kein Verbrauch), damit
        // die Spieler nicht zwischen Duels essen müssen.
        // Außerhalb der Lobby (z.B. Build/Survival-Welt): Vanilla, also
        // gar nichts erzwingen — sonst kann der Spieler dort nicht
        // normal essen/Hunger haben.
        if (plugin.getPlayerManager() != null
                && plugin.getPlayerManager().isInLobbyWorld(player)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20f);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (plugin.getDuelManager().isFrozen(player.getUniqueId())) {
            event.setTo(event.getFrom());
            return;
        }
        // FFA-/Team-Match Pre-Match-Freeze (3s Countdown, wie Duel)
        if (plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isFrozen(player.getUniqueId())) {
            event.setTo(event.getFrom());
            return;
        }

        // Corner-Boundary für aktive Spieler (Duel/FFA/Team): wenn sie die
        // Arena-Corners verlassen würden, werden sie ein kleines Stück zurück
        // an die Kante geklemmt (verhindert Rausbuggen aus der Map).
        java.util.UUID id = player.getUniqueId();
        dev.duels.objects.Arena matchArena = null;
        if (plugin.getDuelManager().isInDuel(id)) {
            matchArena = plugin.getDuelManager().getArenaOf(id);
        } else if (plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(id)) {
            matchArena = plugin.getPartyFFAManager().getArenaOf(id);
        }
        if (matchArena != null) {
            Location clamped = matchArena.clampInside(event.getTo());
            if (clamped != null) {
                event.setTo(clamped);
                return;
            }
        }

        // Spectator-Boundary: wenn der Spectator außerhalb der Arena-Bounds
        // landet (Multiverse-Welt-Teleport, Eingabe oder Flug nach außen),
        // ziehen wir ihn zurück. Bevorzugt per Arena-Corners (kleines Stück
        // zurück an die Kante), sonst Fallback auf Distanz zum Target.
        if (plugin.getSpectateManager() != null
                && plugin.getSpectateManager().isSpectating(player.getUniqueId())) {
            dev.duels.managers.SpectateManager.SpectateInfo info =
                    plugin.getSpectateManager().getInfo(player.getUniqueId());
            if (info != null && info.targetId != null) {
                Player target = Bukkit.getPlayer(info.targetId);
                if (target != null && target.isOnline()) {
                    // Wenn der Spectator nicht in der gleichen Welt wie das
                    // Target ist, ziehen wir ihn sofort zurück.
                    if (!target.getWorld().equals(player.getWorld())) {
                        player.teleport(target.getLocation());
                        return;
                    }
                    // Block-Kollision für Match-Spectator: sie dürfen aus einem
                    // Block RAUS fliegen (z.B. wenn sie in der FFA in einem
                    // Block gestorben sind), aber nicht in einen Block REIN.
                    // -> Bewegung nur abbrechen wenn das Ziel in einem Block
                    //    liegt UND die Startposition NICHT in einem Block lag.
                    // Leute im normalen SPECTATOR-GameMode (kein Match) sind
                    // hier nicht erfasst (nicht in der SpectateManager-Map).
                    if (plugin.getConfigManager().isSpectatorBlockCollision()) {
                        Location to = event.getTo();
                        Location from = event.getFrom();
                        if (to != null && isInsideSolid(to) && !isInsideSolid(from)) {
                            event.setTo(from);
                            return;
                        }
                    }
                    // Arena-Corners des Targets als Grenze nutzen.
                    dev.duels.objects.Arena specArena = null;
                    if (plugin.getDuelManager().isInDuel(target.getUniqueId())) {
                        specArena = plugin.getDuelManager().getArenaOf(target.getUniqueId());
                    } else if (plugin.getPartyFFAManager() != null
                            && plugin.getPartyFFAManager().isParticipant(target.getUniqueId())) {
                        specArena = plugin.getPartyFFAManager().getArenaOf(target.getUniqueId());
                    }
                    if (specArena != null) {
                        Location clamped = specArena.clampInside(event.getTo());
                        if (clamped != null) {
                            event.setTo(clamped);
                            return;
                        }
                    }
                    // Fallback: Distance-Check > 80 Blöcke vom Target → zurück.
                    if (player.getLocation().distanceSquared(target.getLocation()) > 80 * 80) {
                        player.teleport(target.getLocation());
                    }
                }
            }
        }
    }

    /**
     * Prüft ob die Position (Füße oder Kopf) in einem nicht-passierbaren
     * Block liegt. Barrier zählt als nicht-passierbar.
     */
    private boolean isInsideSolid(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        org.bukkit.block.Block feet = loc.getBlock();
        org.bukkit.block.Block head = feet.getRelative(0, 1, 0);
        return !feet.isPassable() || !head.isPassable();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        // Only react on RIGHT CLICK (prevents "hit air with sword" triggering this)
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> { }
            default -> {
                return;
            }
        }

        if (event.getItem() == null || !event.getItem().hasItemMeta()) return;

        // 1) Primary dispatch: Action-Tag (PDC) aus der konfigurierbaren Hotbar.
        String action = plugin.getHotbarManager().readAction(event.getItem());
        if (action != null && !action.isEmpty()) {
            handleHotbarAction(event, player, action);
            return;
        }

        // 2) Fallback: Display-Name-Matching (für Items ohne Tag, z.B. alte
        //    Inventare während eines Plugin-Updates).
        String displayName = event.getItem().getItemMeta().getDisplayName();
        if (displayName == null) return;
        handleLegacyDisplayName(event, player, displayName);
    }

    /**
     * Linksklick mit dem Lobby-Schwert (CHALLENGE-getaggtes Item) auf einen
     * anderen Spieler → öffnet die Kit-Auswahl-GUI für ein direktes 1v1.
     * Linksklick auf einen Spieler ist in Bukkit ein
     * {@link org.bukkit.event.entity.EntityDamageByEntityEvent} — wir
     * canceln den Damage und öffnen stattdessen die GUI.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onAttackEntity(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        // ignoreCancelled=false + LOWEST: läuft AUCH wenn ein Anti-PvP-Plugin
        // den Hit cancelt (das Event geht trotzdem durch, nur cancelled=true).
        // Wir lesen den Hit selbst, öffnen die GUI und canceln den Damage.
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof Player target)) return;

        org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir() || !hand.hasItemMeta()) return;

        String action = plugin.getHotbarManager().readAction(hand);
        if (!"CHALLENGE".equals(action)) return;

        // Nur in der Lobby-Welt + nicht selber im Duel/FFA.
        if (plugin.getDuelManager().isInDuel(player.getUniqueId())) return;
        if (!plugin.getPlayerManager().isInLobbyWorld(player)) return;
        if (player.equals(target)) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        if (plugin.getDuelManager().isInDuel(target.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().prefixed("duel.target-in-duel", "&c{player} is already in a duel.", java.util.Map.of("player", target.getName())));
            return;
        }
        plugin.getGuiManager().openDuelGUI(player, target);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
    }

    private void handleHotbarAction(PlayerInteractEvent event, Player player, String action) {
        event.setCancelled(true);
        switch (action) {
            case "CHALLENGE" -> {
                if (!plugin.getDuelManager().isInDuel(player.getUniqueId())) {
                    plugin.getGuiManager().openQueueGUI(player);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                }
            }
            case "QUEUE_DYNAMIC" -> {
                // Item ist dynamisch: entweder "Leave" (wenn in queue) oder "Rejoin last".
                String meta = event.getItem().getItemMeta().getDisplayName();
                if (meta != null && meta.contains("ʟᴇᴀᴠᴇ")) {
                    plugin.getQueueManager().leaveQueue(player);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 0.8f);
                } else {
                    String lastKit = plugin.getQueueManager().getLastQueueKit(player.getUniqueId());
                    if (lastKit != null) {
                        plugin.getQueueManager().joinQueue(player, lastKit);
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                    } else {
                        player.sendMessage(plugin.getConfigManager().prefixed("queue.no-last-kit", "&cNo last queue kit saved yet!"));
                    }
                }
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> plugin.getPlayerManager().refreshQueueSlotItem(player), 1L);
            }
            case "STATS" -> {
                plugin.getGuiManager().openStatsGUI(player);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            }
            case "SETTINGS" -> {
                plugin.getGuiManager().openSettingsGUI(player);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            }
            case "VISIBILITY" -> {
                plugin.getPlayerManager().toggleVisibility(player.getUniqueId());
                plugin.getPlayerManager().updatePlayerVisibility(player);
                plugin.getPlayerManager().applyVisibility(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            }
            case "PARTY_CREATE" -> {
                if (plugin.getPartyManager().isInParty(player.getUniqueId())) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.already-in", "&cYou are already in a party."));
                } else {
                    plugin.getPartyManager().createParty(player);
                    plugin.getHotbarManager().applyMode(player,
                            dev.duels.managers.HotbarManager.MODE_PARTY_LEADER);
                    player.sendMessage(plugin.getConfigManager().prefixed("party.created",
                            "&dParty &7created! Use &e/party invite <player> &7to invite players."));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
                }
            }
            case "PARTY_MENU" -> {
                if (!plugin.getPartyManager().isLeader(player.getUniqueId())) {
                    player.sendMessage(plugin.getConfigManager().prefixed("party.only-leader-menu", "&cOnly the leader can open this menu."));
                    return;
                }
                plugin.getGuiManager().openPartyMenu(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
            }
            case "PARTY_INVITE" -> {
                player.sendMessage(plugin.getConfigManager().prefixed("party.usage-invite-alt", "&7Usage: &e/party invite <player>"));
            }
            case "PARTY_PUBLIC" -> {
                plugin.getPartyManager().togglePublic(player);
            }
            case "PARTY_INFO" -> {
                player.performCommand("party info");
            }
            case "PARTY_LEAVE" -> {
                if (plugin.getPartyManager().isLeader(player.getUniqueId())) {
                    var party = plugin.getPartyManager().getPartyByLeader(player.getUniqueId());
                    if (party != null) plugin.getPartyManager().disband(party);
                } else {
                    plugin.getPartyManager().leaveParty(player);
                }
            }
            default -> {
                // Custom-Command-Action: `action: "COMMAND:<cmd>"` in der
                // hotbar-Config führt <cmd> als Spieler-Command aus. Damit
                // kann der Admin eigene Hotbar-Slots mit beliebigen Commands
                // hinzufügen (z.B. `/spawn`, `/shop`, `/warp pvp`).
                if (action != null && action.startsWith("COMMAND:")) {
                    String cmd = action.substring("COMMAND:".length()).trim();
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    if (!cmd.isEmpty()) {
                        player.performCommand(cmd);
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                    }
                    return;
                }
                // Unbekannte Action — Fallback auf Display-Name
                String dn = event.getItem().getItemMeta().getDisplayName();
                if (dn != null) handleLegacyDisplayName(event, player, dn);
            }
        }
    }

    private void handleLegacyDisplayName(PlayerInteractEvent event, Player player, String displayName) {
        if (displayName.contains("ʟᴇᴀᴠᴇ ǫᴜᴇᴜᴇ")) {
            event.setCancelled(true);
            plugin.getQueueManager().leaveQueue(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 0.8f);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> plugin.getPlayerManager().refreshQueueSlotItem(player), 1L);
            return;
        }
        if (displayName.contains("ᴊᴏɪɴ ʟᴀѕᴛ ǫᴜᴇᴜᴇ")) {
            event.setCancelled(true);
            String lastKit = plugin.getQueueManager().getLastQueueKit(player.getUniqueId());
            if (lastKit != null) {
                plugin.getQueueManager().joinQueue(player, lastKit);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> plugin.getPlayerManager().refreshQueueSlotItem(player), 1L);
            } else {
                player.sendMessage(plugin.getConfigManager().prefixed("queue.no-last-kit", "&cNo last queue kit saved yet!"));
            }
            return;
        }
        if (displayName.contains("ᴄʜᴀʟʟᴇɴɢᴇ")) {
            event.setCancelled(true);
            if (!plugin.getDuelManager().isInDuel(player.getUniqueId())) {
                plugin.getGuiManager().openQueueGUI(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
            }
            return;
        }
        if (displayName.contains("ѕᴛᴀᴛѕ")) {
            event.setCancelled(true);
            plugin.getGuiManager().openStatsGUI(player);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            return;
        }
        if (displayName.contains("ѕᴇᴛᴛɪɴɢѕ")) {
            event.setCancelled(true);
            plugin.getGuiManager().openSettingsGUI(player);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            return;
        }
        if (displayName.contains("ᴘʟᴀʏᴇʀ ᴠɪѕɪʙɪʟɪᴛʏ")) {
            event.setCancelled(true);
            plugin.getPlayerManager().toggleVisibility(player.getUniqueId());
            plugin.getPlayerManager().updatePlayerVisibility(player);
            plugin.getPlayerManager().applyVisibility(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
        }
    }

    private void forceLobbyState(Player player) {
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setRemainingAir(player.getMaximumAir());
        player.setFreezeTicks(0);
        player.setAbsorptionAmount(0.0);

        player.getActivePotionEffects().forEach(effect ->
                player.removePotionEffect(effect.getType()));

        player.setGameMode(GameMode.SURVIVAL);
    }

    private void forceRoundState(Player player) {
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setRemainingAir(player.getMaximumAir());
        player.setFreezeTicks(0);

        player.getActivePotionEffects().forEach(effect ->
                player.removePotionEffect(effect.getType()));

        player.setAbsorptionAmount(0.0);
    }


    private void spawnParticlesCircle(Player player) {
        Location loc = player.getLocation().clone().add(0, 1, 0);
        new BukkitRunnable() {
            double t = 0;
            final double radius = 1.2;
            final int points = 20;
            final int duration = 40;

            @Override
            public void run() {
                if (t > duration) {
                    cancel();
                    return;
                }
                for (int i = 0; i < points; i++) {
                    double angle = 2 * Math.PI * i / points + t * 0.1;
                    double x = radius * Math.cos(angle);
                    double z = radius * Math.sin(angle);
                    loc.getWorld().spawnParticle(Particle.END_ROD,
                            loc.clone().add(x, 0, z), 0, 0, 0, 0, 0);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    /**
     * Spectator-TP-Einschränkung: Spectators dürfen sich nur zu Spielern
     * teleportieren die Teil des beobachteten Matches sind (User-Wunsch:
     * "spectator sollen sich nicht zu anderen spielern tp können").
     */
    @EventHandler
    public void onSpectatorTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (event.getCause() != org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.SPECTATE)
            return;
        Player spectator = event.getPlayer();
        if (plugin.getSpectateManager() == null
                || !plugin.getSpectateManager().isSpectating(spectator.getUniqueId()))
            return;

        Location to = event.getTo();
        if (to == null) return;

        // Ziel-Spieler in der Nähe des TP-Ziels finden
        boolean allowed = false;
        String matchKey = plugin.getSpectateManager().getMatchKey(spectator.getUniqueId());
        if (matchKey != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.equals(spectator)) continue;
                String pKey = plugin.getSpectateManager().computeMatchKey(p);
                if (matchKey.equals(pKey) && p.getLocation().distance(to) < 5.0) {
                    allowed = true;
                    break;
                }
            }
        }

        if (!allowed) {
            event.setCancelled(true);
            spectator.sendMessage(plugin.getConfigManager().prefixed("spectate.tp-only-match", "&cYou can only teleport to players in your match."));
        }
    }
}
