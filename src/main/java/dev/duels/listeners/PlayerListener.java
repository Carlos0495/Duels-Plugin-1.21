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
                    plugin.getKitManager().giveKit(player,
                            plugin.getDuelManager().getDuelSession(uuid).getKitName());
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
                spawnParticlesCircle(player);
                plugin.getPlayerManager().applyLobbyFly(player);
            }
        }, 1L);
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
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();

        if (event.getNewGameMode() == GameMode.SURVIVAL) {
            if (!plugin.getDuelManager().isInDuel(player.getUniqueId())) {
                plugin.getPlayerManager().setupPlayerInventory(player);
            }
        } else if (event.getNewGameMode() == GameMode.CREATIVE) {
            player.getInventory().clear();
        }
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (!plugin.getDuelManager().isInDuel(player.getUniqueId())) {
                event.setCancelled(true);
                player.setFoodLevel(20);
                player.setSaturation(20f);
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (plugin.getDuelManager().isFrozen(player.getUniqueId())) {
            event.setTo(event.getFrom());
        }
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
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttackEntity(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
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
            player.sendMessage(plugin.getPrefix() + "§c" + target.getName() + " is already in a duel.");
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
                        player.sendMessage(plugin.getPrefix() + "§cNo last queue kit saved yet!");
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
                    player.sendMessage(plugin.getPrefix() + "§cYou are already in a party.");
                } else {
                    plugin.getPartyManager().createParty(player);
                    plugin.getHotbarManager().applyMode(player,
                            dev.duels.managers.HotbarManager.MODE_PARTY_LEADER);
                    player.sendMessage(plugin.getPrefix()
                            + "§dParty §7created! Use §e/party invite <player> §7to invite players.");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
                }
            }
            case "PARTY_MENU" -> {
                if (!plugin.getPartyManager().isLeader(player.getUniqueId())) {
                    player.sendMessage(plugin.getPrefix() + "§cOnly the leader can open this menu.");
                    return;
                }
                plugin.getGuiManager().openPartyMenu(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
            }
            case "PARTY_INVITE" -> {
                player.sendMessage(plugin.getPrefix() + "§7Usage: §e/party invite <player>");
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
                player.sendMessage(plugin.getPrefix() + "§cNo last queue kit saved yet!");
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
}
