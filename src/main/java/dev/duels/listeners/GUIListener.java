package dev.duels.listeners;

import dev.duels.DuelsPlugin;
import dev.duels.guis.GUIManager;
import dev.duels.managers.DuelManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.inventory.InventoryAction;
import dev.duels.objects.DuelRequest;
import java.util.UUID;
import java.util.Set;

import java.util.List;

public class GUIListener implements Listener {

    private final DuelsPlugin plugin;
    private final java.util.Map<UUID, Long> flyCooldown = new java.util.HashMap<>();

    private final NamespacedKey queueKitKey;
    private final NamespacedKey previewKitKey;
    private final NamespacedKey duelKitKey;
    private final NamespacedKey editKitKey;
    private final NamespacedKey bestOfValueKey;
    private final NamespacedKey duelTargetKey;
    private final Set<UUID> awaitingStatsSearch = new java.util.HashSet<>();


    public GUIListener(DuelsPlugin plugin) {
        this.plugin = plugin;

        this.queueKitKey = new NamespacedKey(plugin, "queue_kit");
        this.previewKitKey = new NamespacedKey(plugin, "preview_kit");
        this.duelKitKey = new NamespacedKey(plugin, "duel_kit");
        this.editKitKey = new NamespacedKey(plugin, "edit_kit");
        this.bestOfValueKey = new NamespacedKey(plugin, "bestof_value");
        this.duelTargetKey = new NamespacedKey(plugin, "duel_target");
    }

    /** Prüft ob ein Item die gegebene GUI-Item-ID hat (PDC-Tag). */
    private boolean isGuiItem(ItemStack item, String id) {
        var gc = plugin.getGuiConfig();
        if (gc != null && gc.hasItemId(item, id)) return true;
        // Fallback: Name-Match für Items die ohne GuiConfig gebaut wurden
        if (item == null || !item.hasItemMeta()) return false;
        String name = item.getItemMeta().getDisplayName();
        if (name == null) return false;
        // Map ID -> legacy display name
        return switch (id) {
            case "queue-gui.close", "kits-gui.close", "settings-gui.close",
                 "edit-layouts-gui.close", "edit-layout-gui.close",
                 "bestof-gui.close", "duel-gui.close", "stats-gui.close",
                 "compare-gui.close" -> name.contains("§cClose");
            case "queue-gui.info" -> name.equals("§6Queue: Select a Kit");
            case "kits-gui.info" -> name.equals("§6Select a Kit");
            case "kits-gui.no-kits" -> name.contains("§cNo Kits Available");
            case "duel-gui.info" -> name.equals("§6Select a Kit");
            case "settings-gui.edit-layouts" -> name.equals("§aEdit Kit Inventory Layouts");
            case "settings-gui.auto-fly-name" -> name.equals("§bAuto Fly");
            case "edit-layouts-gui.info" -> name.equals("§6Select a Kit to Edit");
            case "edit-layout-gui.save" -> name.contains("§aSave Layout");
            case "edit-layout-gui.reset" -> name.contains("§cReset to Default");
            default -> false;
        };
    }
    @EventHandler
    public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Custom Kit Name Eingabe.
        if (plugin.getGuiManager().getPendingCustomKitName().containsKey(uuid)) {
            event.setCancelled(true);
            String msg = event.getMessage().trim();
            if (msg.equalsIgnoreCase("cancel")) {
                plugin.getGuiManager().getPendingCustomKitName().remove(uuid);
                plugin.getGuiManager().getPendingCustomKitEditIndex().remove(uuid);
                player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.creation-cancelled", "&7Kit creation cancelled."));
                return;
            }
            if (msg.length() > 32) {
                player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.name-too-long", "&cName too long (max 32 chars). Try again or type &ccancel&c."));
                return;
            }
            plugin.getGuiManager().getPendingCustomKitName().remove(uuid);
            Integer editIdx = plugin.getGuiManager().getPendingCustomKitEditIndex().remove(uuid);
            final String kitName = msg;
            final int editIndex = editIdx != null ? editIdx : -1;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (editIndex >= 0) {
                    // Rename existing kit
                    var ck = plugin.getCustomKitManager().getKit(uuid, editIndex);
                    if (ck != null) {
                        ck.setDisplayName(kitName);
                        plugin.getCustomKitManager().updateKit(ck);
                        player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.renamed", "&7Kit renamed to &e{name}.", java.util.Map.of("name", kitName)));
                    }
                } else {
                    plugin.getGuiManager().beginNewKitBuilder(player, kitName);
                }
            });
            return;
        }

        // Custom-Round-Count Eingabe.
        GUIManager.PendingCustomBestOf pending = plugin.getGuiManager().peekPendingCustomBestOf(uuid);
        if (pending != null) {
            event.setCancelled(true);
            String msg = event.getMessage().trim();
            if (msg.equalsIgnoreCase("cancel")) {
                plugin.getGuiManager().consumePendingCustomBestOf(uuid);
                player.sendMessage(plugin.getConfigManager().prefixed("duel.custom-rounds-cancelled", "&7Custom rounds cancelled."));
                return;
            }
            int n;
            try {
                n = Integer.parseInt(msg);
            } catch (NumberFormatException ex) {
                player.sendMessage(plugin.getConfigManager().prefixed("duel.not-a-number", "&cNot a number. Try again, or &ccancel&c."));
                return;
            }
            if (n < 1 || n > 100) {
                player.sendMessage(plugin.getConfigManager().prefixed("duel.value-range", "&cValue must be between 1 and 100."));
                return;
            }
            plugin.getGuiManager().consumePendingCustomBestOf(uuid);
            final int firstTo = n;
            final UUID targetId = pending.target;
            final String kitId = pending.kitId;
            Bukkit.getScheduler().runTask(plugin, () -> sendCustomDuelRequest(player, targetId, kitId, firstTo));
            return;
        }

        if (!awaitingStatsSearch.contains(uuid)) return;

        event.setCancelled(true);

        String msg = event.getMessage().trim();
        if (msg.equalsIgnoreCase("cancel")) {
            awaitingStatsSearch.remove(uuid);
            player.sendMessage(plugin.getConfigManager().prefixed("general.search-cancelled", "&7Search cancelled."));
            return;
        }

        UUID targetUuid = plugin.getPlayerManager().getUUIDFromName(msg);
        if (targetUuid == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("general.player-not-found", "&cPlayer not found: &7{player}", java.util.Map.of("player", msg)));
            player.sendMessage(plugin.getConfigManager().prefixed("general.try-again-cancel", "&7Try again or type &ccancel&7."));
            return;
        }

        awaitingStatsSearch.remove(uuid);

        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getGuiManager().openCompareGUI(player, targetUuid);
        });
    }

    private void sendCustomDuelRequest(Player sender, UUID targetId, String kitId, int firstTo) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().prefixed("duel.target-offline", "&cTarget player is offline."));
            return;
        }
        String arenaName = plugin.getArenaManager().reserveRandomFreeArenaName();
        if (arenaName == null) {
            sender.sendMessage(plugin.getConfigManager().prefixed("general.no-free-arena", "&cNo free arena available!"));
            return;
        }
        DuelRequest request = new DuelRequest(
                sender.getUniqueId(),
                targetId,
                kitId,
                arenaName,
                firstTo
        );
        plugin.getDuelManager().addDuelRequest(targetId, request);
        sender.sendMessage(plugin.getConfigManager().prefixed("duel.request-sent-firstto",
                "&7Sent duel request to &c{player} &7| Kit: &e{kit} &7| First to &f{value}",
                java.util.Map.of("player", target.getName(), "kit", kitId, "value", String.valueOf(firstTo))));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        awaitingStatsSearch.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = event.getView().getTitle();
        Inventory top = event.getView().getTopInventory();
        Inventory clickedInv = event.getClickedInventory();

        // In Duel - Inventarbewegungen erlauben
        if (plugin.getDuelManager().isInDuel(player.getUniqueId())) {
            return;
        }

        // Custom GUI-Items: left-click / right-click Commands aus guis.yml
        if (clickedInv == top && handleCustomItemClick(event, player)) {
            return;
        }

        // Queue GUI
        if (title.equals(GUIManager.QUEUE_GUI_TITLE)) {
            handleQueueGUIClick(event, player, top, clickedInv);
            return;
        }

        // BestOf GUI
        if (title.equals(GUIManager.BESTOF_GUI_TITLE)) {
            handleBestOfGUIClick(event, player, top, clickedInv);
            return;
        }

        // Armor Trim Editor GUI
        if (title.equals(GUIManager.ARMOR_TRIM_GUI_TITLE)) {
            handleArmorTrimGUIClick(event, player, top, clickedInv);
            return;
        }

        // Edit Layout GUI
        if (title.startsWith(GUIManager.EDIT_LAYOUT_GUI_TITLE_PREFIX)) {
            handleEditLayoutGUIClick(event, player, top, clickedInv);
            return;
        }

        // Edit Layouts Selection GUI
        if (title.equals(GUIManager.EDIT_LAYOUTS_GUI_TITLE)) {
            handleEditLayoutsGUIClick(event, player, top, clickedInv);
            return;
        }

        // Custom Kit GUIs
        if (title.equals(GUIManager.CUSTOM_KIT_LIST_TITLE)) {
            handleCustomKitListClick(event, player, top, clickedInv);
            return;
        }
        if (title.equals(GUIManager.CUSTOM_KIT_COPY_TITLE)) {
            handleCopyKitClick(event, player, top, clickedInv);
            return;
        }
        if (title.startsWith(GUIManager.CUSTOM_KIT_BUILDER_PREFIX)) {
            handleKitBuilderClick(event, player, top, clickedInv);
            return;
        }
        if (title.equals(GUIManager.CUSTOM_KIT_ITEMS_TITLE)) {
            handleItemPickerClick(event, player, top, clickedInv);
            return;
        }
        if (title.equals(GUIManager.CUSTOM_KIT_ENCHANT_SELECT_TITLE)) {
            handleEnchantSelectClick(event, player, top, clickedInv);
            return;
        }
        if (title.startsWith(GUIManager.CUSTOM_KIT_ENCHANT_EDITOR_PREFIX)) {
            handleEnchantEditorClick(event, player, top, clickedInv);
            return;
        }

        // Duel Kit Selection GUI
        if (title.equals(GUIManager.DUEL_GUI_TITLE)) {
            handleDuelGUIClick(event, player, top, clickedInv);
            return;
        }

        // Kits GUI
        if (title.equals(GUIManager.KITS_GUI_TITLE)) {
            handleKitsGUIClick(event, player, top, clickedInv);
            return;
        }

        // Settings GUI
        if (title.equals(GUIManager.SETTINGS_GUI_TITLE)) {
            handleSettingsGUIClick(event, player, top, clickedInv);
            return;
        }

        // Party GUIs
        if (title.equals(GUIManager.PARTY_MENU_TITLE)
                || title.equals(GUIManager.PARTY_SELECT_MEMBER_TITLE)
                || title.equals(GUIManager.PARTY_TEAMS_TITLE)
                || title.equals(GUIManager.PARTY_KIT_SELECT_TITLE)) {
            handlePartyGUIClick(event, player, top, clickedInv, title);
            return;
        }

        // Stats GUI
        if (title.equals(GUIManager.STATS_GUI_TITLE)) {
            event.setCancelled(true);

            int raw = event.getRawSlot();
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            // Close
            if (isGuiItem(clicked, "stats-gui.close")) {
                player.closeInventory();
                plugin.getGuiManager().closeGUI(player.getUniqueId());
                return;
            }

            // Top 5 toggle (slot 11)
            if (raw == 11) {
                // re-open stats GUI (it reads next sort)
                plugin.getGuiManager().cycleStatsSortAndReopen(player);
                return;
            }

            // Search (slot 15)
            String sName = clicked.getItemMeta().getDisplayName();
            if (raw == 15 && sName != null && sName.contains("Search Players")) {
                player.closeInventory();
                plugin.getGuiManager().closeGUI(player.getUniqueId());

                awaitingStatsSearch.add(player.getUniqueId());
                player.sendMessage(plugin.getConfigManager().prefixed("general.type-player-name", "&7Type a player name in chat. &7or Type &ccancel"));
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                return;
            }

            return;
        }
// Compare Stats GUI
        if (title.equals(GUIManager.COMPARE_GUI_TITLE)) {
            event.setCancelled(true);

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            if (isGuiItem(clicked, "compare-gui.close")) {
                player.closeInventory();
                plugin.getGuiManager().closeGUI(player.getUniqueId());
            }
            return;
        }

    }

    private void handleQueueGUIClick(InventoryClickEvent event, Player player, Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();

        // Close Button / Info
        if (isGuiItem(clicked, "queue-gui.close") || isGuiItem(clicked, "queue-gui.info")) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        // Kit Item via PDC
        String kitId = meta.getPersistentDataContainer().get(queueKitKey, PersistentDataType.STRING);
        if (kitId == null || kitId.isEmpty()) return;

        if (plugin.getQueueManager().isInQueue(player.getUniqueId(), kitId)) {
            plugin.getQueueManager().leaveQueue(player);
        } else {
            plugin.getQueueManager().joinQueue(player, kitId);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // refresh GUI
            plugin.getGuiManager().populateQueueGUI(player, top);
            player.updateInventory();

            // refresh hotbar item (slot 4 head/barrier)
            plugin.getPlayerManager().refreshQueueSlotItem(player);
        }, 1L);
    }


    private void handleBestOfGUIClick(InventoryClickEvent event, Player player, Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();
        String name = meta.getDisplayName() == null ? "" : meta.getDisplayName();

        // Close / Back
        if (isGuiItem(clicked, "bestof-gui.close") || name.equals("§cBack")) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        Integer bestOf = pdc.get(bestOfValueKey, PersistentDataType.INTEGER);
        String kitId = pdc.get(duelKitKey, PersistentDataType.STRING);
        String targetStr = pdc.get(duelTargetKey, PersistentDataType.STRING);

        // Custom-Button: kein bestof-Wert gesetzt, aber Kit+Target da → Chat-Prompt.
        if (bestOf == null && kitId != null && !kitId.isEmpty() && targetStr != null && !targetStr.isEmpty()
                && name.contains("Custom")) {
            try {
                UUID tu = UUID.fromString(targetStr);
                player.closeInventory();
                plugin.getGuiManager().closeGUI(player.getUniqueId());
                plugin.getGuiManager().beginCustomBestOfPrompt(player, tu, kitId);
            } catch (IllegalArgumentException ignored) {}
            return;
        }

        if (bestOf == null || kitId == null || kitId.isEmpty() || targetStr == null || targetStr.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().prefixed("duel.missing-data", "&cMissing duel data (bestof/kit/target)."));
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(targetStr);
        } catch (IllegalArgumentException ex) {
            player.sendMessage(plugin.getConfigManager().prefixed("duel.invalid-target", "&cInvalid target data."));
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            player.sendMessage(plugin.getConfigManager().prefixed("duel.target-offline", "&cTarget player is offline."));
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        // Arena auswählen
        String arenaName = plugin.getArenaManager().reserveRandomFreeArenaName();
        if (arenaName == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("general.no-free-arena", "&cNo free arena available!"));
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        DuelRequest request = new DuelRequest(
                player.getUniqueId(),
                target.getUniqueId(),
                kitId,
                arenaName,
                bestOf
        );

        plugin.getDuelManager().addDuelRequest(target.getUniqueId(), request);

        player.closeInventory();
        plugin.getGuiManager().closeGUI(player.getUniqueId());

        // Optional: feedback
        player.sendMessage(plugin.getConfigManager().prefixed("duel.request-sent-bestof",
                "&7Sent duel request to &c{player} &7| Kit: &e{kit} &7| Best of &f{value}",
                java.util.Map.of("player", target.getName(), "kit", kitId, "value", String.valueOf(bestOf))));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
    }


    private void handleEditLayoutGUIClick(InventoryClickEvent event, Player player, Inventory top, Inventory clickedInv) {
        final int raw = event.getRawSlot();

        // Immer: Shift/Quick-move blocken (sonst Copy/Dupe-Effekte)
        if (event.isShiftClick() || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            return;
        }

        // Zahlentasten / Offhand / Doubleclick / Middle blocken (Dupe/Weird).
        // Außerdem Q-Drop / Strg+Q blocken — sonst legt der Spieler das Kit-
        // Item per Q in seinem echten Inventar ab statt zu droppen (Bug-
        // Report: "wenn man bei item sort dropped dann geht es ins inventar").
        switch (event.getClick()) {
            case NUMBER_KEY:
            case SWAP_OFFHAND:
            case DOUBLE_CLICK:
            case MIDDLE:
            case DROP:
            case CONTROL_DROP:
                event.setCancelled(true);
                return;
            default:
                break;
        }

        // Klick im Player-Inventar (bottom): während die Layout-Edit-GUI
        // offen ist KOMPLETT sperren. Sonst kann der Spieler ein Item aus
        // dem Kit-Slot picken (Cursor) und in seinem echten Inventar
        // ablegen → Free-Item-Bug. Items im Inventar bleiben sichtbar,
        // sind aber nicht klickbar während die GUI offen ist.
        if (clickedInv != null && clickedInv.equals(event.getView().getBottomInventory())) {
            event.setCancelled(true);
            return;
        }

        // Nur Top-Inventar behandeln
        if (clickedInv != top) return;

        // Armor-Trim-Editor Button (Slot 50): falls vorhanden öffnen.
        // Wenn der Button locked ist (Spieler ohne duels.armortrim), tut der
        // Klick nichts außer einer kurzen Meldung.
        if (raw == 50) {
            event.setCancelled(true);
            ItemStack btn = event.getCurrentItem();
            if (btn == null || !btn.hasItemMeta()) return;
            PersistentDataContainer pdc = btn.getItemMeta().getPersistentDataContainer();
            if (!pdc.has(plugin.getGuiManager().getArmorTrimOpenKey(), PersistentDataType.STRING)) {
                return;
            }
            if (pdc.has(plugin.getGuiManager().getArmorTrimLockedKey(), PersistentDataType.BYTE)) {
                player.sendMessage(plugin.getConfigManager().prefixed("armortrim.use-no-permission", "&cYou don't have permission to use armor trims."));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                return;
            }
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> plugin.getGuiManager().openArmorTrimGUI(player), 2L);
            return;
        }

        // Buttons (unten rechts): 51/52/53
        if (raw == 51 || raw == 52 || raw == 53) {
            event.setCancelled(true);

            ItemStack button = event.getCurrentItem();
            if (button == null || !button.hasItemMeta()) return;

            String kitId = getEditLayoutKitId(top); // liest Slot 45
            if (kitId == null) {
                player.sendMessage(plugin.getConfigManager().prefixed("layout.no-kit-id", "&cCould not detect kit id for this layout."));
                player.closeInventory();
                plugin.getGuiManager().closeGUI(player.getUniqueId());
                return;
            }

            if (isGuiItem(button, "edit-layout-gui.save")) {
                ItemStack[] layout = new ItemStack[36];
                for (int i = 0; i < 36; i++) {
                    ItemStack it = top.getItem(i);
                    layout[i] = (it == null || it.getType() == Material.AIR) ? null : it.clone();
                }

                plugin.getKitManager().saveCustomLayout(player.getUniqueId(), kitId, layout);
                String kitDisplay = plugin.getKitManager().getKitDisplayName(kitId);

                player.sendMessage(plugin.getConfigManager().prefixed("layout.saved", "&7Inventory layout for kit &r{kit} &asaved!", java.util.Map.of("kit", kitDisplay)));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);

                player.closeInventory();
                plugin.getGuiManager().closeGUI(player.getUniqueId());
                return;
            }

            if (isGuiItem(button, "edit-layout-gui.reset")) {
                String kitDisplay = plugin.getKitManager().getKitDisplayName(kitId);

                plugin.getKitManager().deleteCustomLayout(player.getUniqueId(), kitId);
                player.sendMessage(plugin.getConfigManager().prefixed("layout.reset", "&7Inventory layout for kit &r{kit} &a reset to default!", java.util.Map.of("kit", kitDisplay)));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);

                player.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.getGuiManager().openEditLayoutGUI(player, kitId), 2L);
                return;
            }

            if (isGuiItem(button, "edit-layout-gui.close")) {
                player.closeInventory();
                plugin.getGuiManager().closeGUI(player.getUniqueId());
            }
            return;
        }

        // Sperre Armor/Offhand-Anzeige 36-40 (und allgemein 36-44)
        if (raw >= 36 && raw <= 44) {
            event.setCancelled(true);
            return;
        }

        // Info-Paper Slot 45 sperren (damit kitId drin bleibt)
        if (raw == 45) {
            event.setCancelled(true);
            return;
        }

        // Rest unten (46-50, 54er GUI-filler usw.) sperren
        if (raw >= 46) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(false);
    }



    private void handleEditLayoutsGUIClick(InventoryClickEvent event, Player player, Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();

        if (isGuiItem(clicked, "edit-layouts-gui.close")) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        if (isGuiItem(clicked, "edit-layouts-gui.info")) return;

        // Custom Kits button
        String ckAction = meta.getPersistentDataContainer().getOrDefault(
                plugin.getGuiManager().getCustomKitActionKey(), PersistentDataType.STRING, null);
        if ("OPEN_LIST".equals(ckAction)) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    plugin.getGuiManager().openCustomKitListGUI(player), 2L);
            return;
        }

        String kitId = meta.getPersistentDataContainer().get(editKitKey, PersistentDataType.STRING);
        if (kitId == null || kitId.isEmpty()) return;

        player.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.getGuiManager().openEditLayoutGUI(player, kitId), 2L);
    }

    private void handleDuelGUIClick(InventoryClickEvent event, Player player, Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();

        if (isGuiItem(clicked, "duel-gui.close") || isGuiItem(clicked, "duel-gui.info")) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        String kitId = meta.getPersistentDataContainer().get(duelKitKey, PersistentDataType.STRING);
        if (kitId == null || kitId.isEmpty()) return;

        String targetName = extractTargetFromLore(meta.getLore());
        if (targetName == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("duel.could-not-find-target", "&cCould not find target player!"));
            return;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            player.sendMessage(plugin.getConfigManager().prefixed("duel.target-not-found", "&cTarget player not found or offline!"));
            return;
        }

        if (event.getClick().isRightClick()) {
            plugin.getGuiManager().openBestOfGUI(player, target, kitId);
            return;
        }

        int defaultBestOf = plugin.getConfigManager().getMainConfig().getInt("default-bestof", 1);

// Arena auswählen
        String arenaName = plugin.getArenaManager().reserveRandomFreeArenaName();
        if (arenaName == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("general.no-free-arena", "&cNo free arena available!"));
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        DuelRequest request = new DuelRequest(
                player.getUniqueId(),
                target.getUniqueId(),
                kitId,
                arenaName,
                defaultBestOf
        );

        plugin.getDuelManager().addDuelRequest(target.getUniqueId(), request);

        player.closeInventory();
        plugin.getGuiManager().closeGUI(player.getUniqueId());


        /*
        player.closeInventory();
        player.sendMessage(plugin.getConfigManager().prefixed("duel.sending-request",
                "&7Sending duel request to &c{player}&7 with kit &e{kit}&7 (Best of {value})",
                java.util.Map.of("player", target.getName(), "kit", kitId, "value", String.valueOf(defaultBestOf))));
        plugin.getGuiManager().closeGUI(player.getUniqueId());
        */

    }

    private void handleKitsGUIClick(InventoryClickEvent event, Player player, Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();

        if (isGuiItem(clicked, "kits-gui.close") || isGuiItem(clicked, "kits-gui.info")) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        if (isGuiItem(clicked, "kits-gui.no-kits")) return;

        String kitId = meta.getPersistentDataContainer().get(previewKitKey, PersistentDataType.STRING);
        if (kitId == null || kitId.isEmpty()) return;

        player.closeInventory();
        plugin.getKitManager().giveKitPreview(player, kitId);
        plugin.getGuiManager().closeGUI(player.getUniqueId());
    }

    private void handleSettingsGUIClick(InventoryClickEvent event, Player player, Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        if (isGuiItem(clicked, "settings-gui.edit-layouts")) {
            plugin.getGuiManager().openEditLayoutsGUI(player);
            return;
        }

        if (isGuiItem(clicked, "settings-gui.auto-fly-name")) {
            if (!player.hasPermission("duels.fly")) {
                player.sendMessage(plugin.getConfigManager().prefixed("general.no-permission", "&cYou don't have permission!"));
                return;
            }

            boolean newValue = !plugin.getPlayerManager().getAutoFly(player.getUniqueId());

            // Cooldown beim Aktivieren
            if (newValue) {
                int cooldownSec = plugin.getConfigManager().getMainConfig()
                        .getInt("party.fly-cooldown", 3);
                long now = System.currentTimeMillis();
                Long last = flyCooldown.get(player.getUniqueId());
                if (last != null && (now - last) < cooldownSec * 1000L) {
                    int remaining = (int) ((cooldownSec * 1000L - (now - last)) / 1000) + 1;
                    player.sendMessage(plugin.getConfigManager().prefixed("fly.cooldown", "&cPlease wait &f{seconds}s &cbefore enabling fly again.", java.util.Map.of("seconds", String.valueOf(remaining))));
                    return;
                }
                flyCooldown.put(player.getUniqueId(), now);
            }

            plugin.getPlayerManager().setAutoFly(player.getUniqueId(), newValue);

            player.sendMessage(plugin.getConfigManager().prefixed("fly.auto-toggle", "&7Auto Fly is now {state}", java.util.Map.of("state", newValue ? "&aENABLED" : "&cDISABLED")));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);

            plugin.getPlayerManager().applyLobbyFly(player);

            top.setItem(15, plugin.getGuiManager().createAutoFlyItem(player));
            return;
        }

        if (isGuiItem(clicked, "settings-gui.close")) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
        }
    }

    /**
     * Prüft ob das geklickte Item ein Custom-GUI-Item mit left-click
     * oder right-click Command ist. Gibt {@code true} zurück wenn
     * das Item behandelt wurde.
     */
    private boolean handleCustomItemClick(InventoryClickEvent event, Player player) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return false;

        var gc = plugin.getGuiConfig();
        if (gc == null) return false;

        PersistentDataContainer pdc = clicked.getItemMeta().getPersistentDataContainer();
        String action = null;

        boolean isLeft = event.isLeftClick();
        boolean isRight = event.isRightClick();

        if (isLeft) {
            action = pdc.get(gc.getLeftClickKey(), PersistentDataType.STRING);
        }
        if (action == null && isRight) {
            action = pdc.get(gc.getRightClickKey(), PersistentDataType.STRING);
        }
        if (action == null) return false;

        event.setCancelled(true);

        if (action.startsWith("COMMAND:")) {
            String cmd = action.substring("COMMAND:".length()).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!cmd.isEmpty()) {
                player.closeInventory();
                player.performCommand(cmd);
                player.playSound(player.getLocation(),
                        org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f);
            }
        }
        return true;
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = event.getView().getTitle();

        // In Duel - Drag erlauben
        if (plugin.getDuelManager().isInDuel(player.getUniqueId())) {
            return;
        }

        // Edit Layout GUI - only allow dragging into 0-35 of TOP inventory
        if (title.startsWith(GUIManager.EDIT_LAYOUT_GUI_TITLE_PREFIX)) {
            for (int rawSlot : event.getRawSlots()) {
                // rawSlot < topSize means it's in the top inventory
                if (rawSlot < event.getView().getTopInventory().getSize()) {
                    if (rawSlot >= 36) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            return;
        }

        // Kit Builder: allow drag in editable slots (0-40) only
        if (title.startsWith(GUIManager.CUSTOM_KIT_BUILDER_PREFIX)) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < event.getView().getTopInventory().getSize()) {
                    if (rawSlot > 40) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            return;
        }

        // Block drags in other plugin GUIs
        if (title.equals(GUIManager.QUEUE_GUI_TITLE)
                || title.equals(GUIManager.BESTOF_GUI_TITLE)
                || title.equals(GUIManager.EDIT_LAYOUTS_GUI_TITLE)
                || title.equals(GUIManager.DUEL_GUI_TITLE)
                || title.equals(GUIManager.KITS_GUI_TITLE)
                || title.equals(GUIManager.SETTINGS_GUI_TITLE)
                || title.equals(GUIManager.STATS_GUI_TITLE)
                || title.equals(GUIManager.CUSTOM_KIT_LIST_TITLE)
                || title.equals(GUIManager.CUSTOM_KIT_COPY_TITLE)
                || title.equals(GUIManager.CUSTOM_KIT_ITEMS_TITLE)
                || title.equals(GUIManager.CUSTOM_KIT_ENCHANT_SELECT_TITLE)
                || title.startsWith(GUIManager.CUSTOM_KIT_ENCHANT_EDITOR_PREFIX)) {
            event.setCancelled(true);
        }
    }

    private String getEditLayoutKitId(Inventory top) {
        ItemStack info = top.getItem(45);
        if (info == null || !info.hasItemMeta()) return null;

        PersistentDataContainer pdc = info.getItemMeta().getPersistentDataContainer();
        String kitId = pdc.get(editKitKey, PersistentDataType.STRING);
        return (kitId == null || kitId.isEmpty()) ? null : kitId;
    }

    private String extractTargetFromLore(List<String> lore) {
        if (lore == null || lore.isEmpty()) return null;

        for (String line : lore) {
            if (!line.contains("Challenge §c")) continue;

            String[] parts = line.split("§c", 2);
            if (parts.length < 2) continue;

            String targetPart = ChatColor.stripColor(parts[1]).trim();
            if (targetPart.isEmpty()) continue;

            String[] words = targetPart.split(" ");
            if (words.length > 0) return words[0];
        }
        return null;
    }

    // ===================== Custom Kit Handlers =====================

    private void handleCustomKitListClick(InventoryClickEvent event, Player player,
                                          Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        String action = pdc.getOrDefault(
                plugin.getGuiManager().getCustomKitActionKey(),
                PersistentDataType.STRING, null);

        // Close
        if (clicked.getType() == Material.BARRIER) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        if ("CREATE".equals(action)) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            // Ask for kit name via chat
            plugin.getGuiManager().getPendingCustomKitName().put(player.getUniqueId(), true);
            plugin.getGuiManager().getPendingCustomKitEditIndex().put(player.getUniqueId(), -1);
            player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.enter-name",
                    "&7Enter a name for your custom kit in chat. Type &ccancel &7to abort."));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
            return;
        }

        if ("EDIT".equals(action)) {
            Integer idx = pdc.get(plugin.getGuiManager().getCustomKitIndexKey(), PersistentDataType.INTEGER);
            if (idx == null) return;

            if (event.isShiftClick()) {
                // Delete
                plugin.getCustomKitManager().deleteKit(player.getUniqueId(), idx);
                player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.deleted", "&cCustom kit deleted."));
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                // Refresh
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                        plugin.getGuiManager().openCustomKitListGUI(player), 2L);
            } else {
                // Edit
                player.closeInventory();
                plugin.getGuiManager().closeGUI(player.getUniqueId());
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                        plugin.getGuiManager().beginEditKitBuilder(player, idx), 2L);
            }
        }
    }

    private void handleCopyKitClick(InventoryClickEvent event, Player player,
                                    Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        if (clicked.getType() == Material.BARRIER) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        String action = pdc.getOrDefault(
                plugin.getGuiManager().getCustomKitActionKey(),
                PersistentDataType.STRING, null);
        if (action == null || !action.startsWith("COPY:")) return;

        String[] parts = action.split(":");
        if (parts.length != 3) return;
        java.util.UUID ownerId;
        int ownerKitIndex;
        try {
            ownerId = java.util.UUID.fromString(parts[1]);
            ownerKitIndex = Integer.parseInt(parts[2]);
        } catch (IllegalArgumentException e) { return; }

        String ownerName = Bukkit.getOfflinePlayer(ownerId).getName();
        if (ownerName == null) ownerName = "?";

        dev.duels.managers.CustomKitManager.CopyResult result =
                plugin.getCustomKitManager().copyKitToSelf(player, ownerId, ownerKitIndex);
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-success",
                        "&aCopied a custom kit from &e{owner} &ainto your kits.",
                        java.util.Map.of("owner", ownerName)));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
                player.closeInventory();
                plugin.getGuiManager().closeGUI(player.getUniqueId());
            }
            case NO_SLOT -> player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-no-slot",
                    "&cYou have no free custom kit slot to copy into."));
            case NO_PERMISSION -> player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.no-permission",
                    "&cYou don't have permission to create custom kits."));
            case NO_SUCH_KIT -> player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.copy-owner-empty",
                    "&e{owner} &chas no custom kits.", java.util.Map.of("owner", ownerName)));
        }
    }

    private void handleKitBuilderClick(InventoryClickEvent event, Player player,
                                       Inventory top, Inventory clickedInv) {
        int raw = event.getRawSlot();

        // Block shift-click / number-key / drop / etc. to prevent item leaking
        if (event.isShiftClick() || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            return;
        }
        switch (event.getClick()) {
            case NUMBER_KEY, SWAP_OFFHAND, DOUBLE_CLICK, MIDDLE, DROP, CONTROL_DROP -> {
                event.setCancelled(true);
                return;
            }
            default -> {}
        }

        // Block clicks in player inventory (bottom)
        if (clickedInv != null && clickedInv.equals(event.getView().getBottomInventory())) {
            event.setCancelled(true);
            return;
        }

        if (clickedInv != top) return;

        // Editable slots: 0-40 (let Minecraft handle item moving)
        if (raw >= 0 && raw <= 40) {
            // Allow normal click behavior (place/pick items in these slots)
            return;
        }

        // Everything below = buttons → cancel
        event.setCancelled(true);

        // Separators (41-44) — no action
        if (raw >= 41 && raw <= 44) return;

        // Info paper (45) — no action
        if (raw == 45) return;

        // Add Items button (46)
        if (raw == 46) {
            // Save current state first
            plugin.getGuiManager().saveBuilderStateFromGUI(top, player.getUniqueId());
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    plugin.getGuiManager().openItemPickerGUI(player, 0), 2L);
            return;
        }

        // Enchant button (47)
        if (raw == 47) {
            plugin.getGuiManager().saveBuilderStateFromGUI(top, player.getUniqueId());
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    plugin.getGuiManager().openEnchantSelectGUI(player), 2L);
            return;
        }

        // Set Icon (48) — click with cursor item to set icon
        if (raw == 48) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                var session = plugin.getGuiManager().getBuilderSessions().get(player.getUniqueId());
                if (session != null) {
                    session.icon = cursor.getType();
                    player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.icon-set",
                            "&7Kit icon set to &e{icon}", java.util.Map.of("icon", cursor.getType().name())));
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                    // Refresh builder
                    plugin.getGuiManager().saveBuilderStateFromGUI(top, player.getUniqueId());
                    player.closeInventory();
                    plugin.getGuiManager().closeGUI(player.getUniqueId());
                    Bukkit.getScheduler().runTaskLater(plugin, () ->
                            plugin.getGuiManager().openKitBuilderGUI(player), 2L);
                }
            }
            return;
        }

        // Save (51)
        if (raw == 51) {
            plugin.getGuiManager().saveBuilderStateFromGUI(top, player.getUniqueId());
            if (plugin.getGuiManager().commitBuilderSession(player)) {
                player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.saved",
                        "&aCustom kit saved!"));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            }
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            plugin.getGuiManager().clearBuilderSession(player.getUniqueId());
            return;
        }

        // Close (53)
        if (raw == 53) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            plugin.getGuiManager().clearBuilderSession(player.getUniqueId());
        }
    }

    private void handleItemPickerClick(InventoryClickEvent event, Player player,
                                       Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        int raw = event.getRawSlot();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // Navigation buttons (check PDC)
        if (clicked.hasItemMeta()) {
            var pdc = clicked.getItemMeta().getPersistentDataContainer();
            String action = pdc.getOrDefault(
                    plugin.getGuiManager().getCustomKitActionKey(),
                    PersistentDataType.STRING, null);

            if ("PREV_PAGE".equals(action)) {
                plugin.getGuiManager().openItemPickerGUI(player,
                        Math.max(0, getItemPickerPage(player) - 1));
                return;
            }
            if ("NEXT_PAGE".equals(action)) {
                plugin.getGuiManager().openItemPickerGUI(player, getItemPickerPage(player) + 1);
                return;
            }
            if (action != null && action.startsWith("CAT:")) {
                String catId = action.substring("CAT:".length());
                plugin.getGuiManager().setItemPickerCategory(player.getUniqueId(), catId);
                plugin.getGuiManager().openItemPickerGUI(player, 0);
                return;
            }
            if ("BACK_TO_BUILDER".equals(action)) {
                player.closeInventory();
                plugin.getGuiManager().closeGUI(player.getUniqueId());
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                        plugin.getGuiManager().openKitBuilderGUI(player), 2L);
                return;
            }
        }

        // Item slot (9-44) — add to kit builder (0-8 are category tabs)
        if (raw >= 9 && raw < 45 && clicked.getType() != Material.AIR) {
            var session = plugin.getGuiManager().getBuilderSessions().get(player.getUniqueId());
            if (session == null) return;

            Material mat = clicked.getType();
            // Find first free slot in builder (0-35)
            int freeSlot = -1;
            for (int i = 0; i < 36; i++) {
                if (!session.items.containsKey(i)) { freeSlot = i; break; }
            }
            if (freeSlot < 0) {
                player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.inventory-full",
                        "&cKit inventory is full! Remove items first."));
                return;
            }

            // Clone the picker item so potion/effect meta is preserved, then strip
            // the cosmetic picker lore and stack it up to the material max.
            ItemStack added = clicked.clone();
            org.bukkit.inventory.meta.ItemMeta am = added.getItemMeta();
            if (am != null) { am.setLore(null); added.setItemMeta(am); }
            added.setAmount(mat.getMaxStackSize());
            session.items.put(freeSlot, added);
            player.sendMessage(plugin.getConfigManager().prefixed("custom-kit.item-added",
                    "&7Added &e{item} &7to slot {slot}.",
                    java.util.Map.of("item", mat.name(), "slot", String.valueOf(freeSlot))));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        }
    }

    private int getItemPickerPage(Player player) {
        return plugin.getGuiManager().getItemPickerPage(player.getUniqueId());
    }

    private void handleEnchantSelectClick(InventoryClickEvent event, Player player,
                                          Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        var pdc = clicked.getItemMeta().getPersistentDataContainer();

        // Back button
        String action = pdc.getOrDefault(
                plugin.getGuiManager().getCustomKitActionKey(),
                PersistentDataType.STRING, null);
        if ("BACK_TO_BUILDER".equals(action)) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    plugin.getGuiManager().openKitBuilderGUI(player), 2L);
            return;
        }

        // Enchant slot selection
        Integer slotKey = pdc.get(plugin.getGuiManager().getCustomKitEnchantSlotKey(), PersistentDataType.INTEGER);
        if (slotKey != null) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    plugin.getGuiManager().openEnchantEditorGUI(player, slotKey), 2L);
        }
    }

    private void handleEnchantEditorClick(InventoryClickEvent event, Player player,
                                          Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        var pdc = clicked.getItemMeta().getPersistentDataContainer();

        // Done button
        String action = pdc.getOrDefault(
                plugin.getGuiManager().getCustomKitActionKey(),
                PersistentDataType.STRING, null);
        if ("ENCHANT_DONE".equals(action)) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    plugin.getGuiManager().openEnchantSelectGUI(player), 2L);
            return;
        }

        // Enchantment click (increase/decrease level)
        String enchKey = pdc.getOrDefault(
                plugin.getGuiManager().getCustomKitEnchantKey(),
                PersistentDataType.STRING, null);
        if (enchKey == null) return;

        var session = plugin.getGuiManager().getBuilderSessions().get(player.getUniqueId());
        if (session == null) return;

        int enchSlot = session.selectedEnchantSlot;
        ItemStack target = session.items.get(enchSlot);
        if (target == null) return;

        org.bukkit.enchantments.Enchantment ench = org.bukkit.Registry.ENCHANTMENT.get(
                org.bukkit.NamespacedKey.minecraft(enchKey));
        if (ench == null) return;

        int currentLevel = target.getEnchantmentLevel(ench);
        int maxLevel = ench.getMaxLevel();

        if (event.isRightClick()) {
            // Decrease
            if (currentLevel > 0) {
                target.removeEnchantment(ench);
                if (currentLevel > 1) {
                    target.addUnsafeEnchantment(ench, currentLevel - 1);
                }
            }
        } else {
            // Increase
            if (currentLevel < maxLevel) {
                target.addUnsafeEnchantment(ench, currentLevel + 1);
            }
        }

        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.2f);

        // Refresh the enchant editor GUI
        player.closeInventory();
        plugin.getGuiManager().closeGUI(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                plugin.getGuiManager().openEnchantEditorGUI(player, enchSlot), 2L);
    }

    // ----------------- Party GUIs -----------------

    private void handlePartyGUIClick(InventoryClickEvent event, Player player,
                                     Inventory top, Inventory clickedInv, String title) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        PersistentDataContainer pdc = clicked.getItemMeta().getPersistentDataContainer();
        String menuAction = pdc.get(plugin.getGuiManager().getPartyActionKey(), PersistentDataType.STRING);
        String memberId = pdc.get(plugin.getGuiManager().getPartyMemberKey(), PersistentDataType.STRING);
        String kitId = pdc.get(plugin.getGuiManager().getPartyKitKey(), PersistentDataType.STRING);
        String pendingAction = pdc.get(plugin.getGuiManager().getPartyPendingActionKey(), PersistentDataType.STRING);

        // Close button
        String displayName = clicked.getItemMeta().getDisplayName();
        if (isGuiItem(clicked, "party-menu.close")
                || (displayName != null && (displayName.contains("§cClose") || displayName.equals("§7Close")))) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        var partyMgr = plugin.getPartyManager();
        var party = partyMgr.getPartyByLeader(player.getUniqueId());
        if (party == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("party.no-longer-leader", "&cYou are no longer a party leader."));
            player.closeInventory();
            return;
        }

        // 1) Party-Menü -> Mode-Auswahl
        if (title.equals(GUIManager.PARTY_MENU_TITLE)) {
            if (menuAction == null) return;
            switch (menuAction) {
                case "DUEL_ONE" -> plugin.getGuiManager().openPartyMemberSelect(player, "DUEL_ONE");
                case "FFA" -> plugin.getGuiManager().openPartyKitSelect(player, "FFA", null);
                case "TEAMS" -> plugin.getGuiManager().openPartyTeamsGUI(player);
                case "PUBLIC" -> {
                    partyMgr.togglePublic(player);
                    plugin.getGuiManager().openPartyMenu(player);
                }
                case "DISBAND" -> {
                    partyMgr.disband(party);
                    player.closeInventory();
                }
            }
            return;
        }

        // 2) Member-Selection (für DUEL_ONE)
        if (title.equals(GUIManager.PARTY_SELECT_MEMBER_TITLE)) {
            if (memberId == null) return;
            // Weiter zur Kit-Selection mit dem Target im pending
            plugin.getGuiManager().openPartyKitSelect(player, "DUEL_ONE", memberId);
            return;
        }

        // 3) Team-Assignment GUI
        if (title.equals(GUIManager.PARTY_TEAMS_TITLE)) {
            if (menuAction != null) {
                if (menuAction.equals("TEAMS_RESET")) {
                    party.resetTeams();
                    plugin.getGuiManager().openPartyTeamsGUI(player);
                    return;
                }
                if (menuAction.equals("TEAMS_START")) {
                    plugin.getGuiManager().openPartyKitSelect(player, "TEAMS_START", null);
                    return;
                }
            }
            if (memberId != null) {
                try {
                    UUID uuid = UUID.fromString(memberId);
                    if (event.isShiftClick()) {
                        party.setTeam(uuid, 0);
                    } else if (event.isRightClick()) {
                        party.setTeam(uuid, 2);
                    } else {
                        party.setTeam(uuid, 1);
                    }
                    plugin.getGuiManager().openPartyTeamsGUI(player);
                } catch (IllegalArgumentException ignored) {}
            }
            return;
        }

        // 4) Kit-Selection -> Duel(s) starten
        if (title.equals(GUIManager.PARTY_KIT_SELECT_TITLE)) {
            if (kitId == null || pendingAction == null) return;

            int bestOf = plugin.getConfigManager().getMainConfig().getInt("default-bestof", 1);

            switch (pendingAction) {
                case "DUEL_ONE" -> {
                    if (memberId == null) {
                        player.sendMessage(plugin.getConfigManager().prefixed("duel.missing-target", "&cMissing target."));
                        return;
                    }
                    try {
                        UUID targetId = UUID.fromString(memberId);
                        Player target = Bukkit.getPlayer(targetId);
                        if (target == null) {
                            player.sendMessage(plugin.getConfigManager().prefixed("duel.target-offline-short", "&cTarget is offline."));
                            return;
                        }
                        plugin.getDuelManager().startPartyDuelOne(player, target, kitId, bestOf);
                        player.closeInventory();
                    } catch (IllegalArgumentException ignored) {}
                }
                case "FFA" -> {
                    // Party-FFA: alle auf EINER Map (User-Wunsch).
                    // Multi-Map: PartyFFAManager.start() reserviert eine
                    // freie Arena mit eigenem FFA-Spawn passend zum Kit.
                    // Setup-Hinweise lässt der Manager als String zurück.
                    java.util.List<Player> members = new java.util.ArrayList<>();
                    for (UUID m : party.getMembers()) {
                        Player p = Bukkit.getPlayer(m);
                        if (p != null && p.isOnline()) members.add(p);
                    }
                    String err = plugin.getPartyFFAManager().start(party, kitId, members);
                    if (err != null) {
                        player.sendMessage(plugin.getConfigManager().prefixed("general.error", "&c{error}", java.util.Map.of("error", err)));
                    } else {
                        plugin.getPartyManager().broadcast(party, plugin.getConfigManager().getMessage("ffa.arena-started", "&6FFA Arena &7started!"));
                    }
                    player.closeInventory();
                }
                case "TEAMS_START" -> {
                    java.util.List<Player> t1 = new java.util.ArrayList<>();
                    java.util.List<Player> t2 = new java.util.ArrayList<>();
                    for (UUID m : party.getMembers()) {
                        Player p = Bukkit.getPlayer(m);
                        if (p == null || !p.isOnline()) continue;
                        int t = party.getTeam(m);
                        if (t == 1) t1.add(p);
                        else if (t == 2) t2.add(p);
                    }
                    if (t1.isEmpty() || t2.isEmpty()) {
                        player.sendMessage(plugin.getConfigManager().prefixed("team.need-players", "&cBoth teams must have at least one player."));
                        return;
                    }
                    // Team-vs-Team läuft jetzt als EIN Match auf EINER Arena
                    // (vorher: n parallele 1v1-Pairs = effektiv 1v1, User-Bug).
                    // PartyFFAManager.startTeams blockt Friendly Fire und
                    // entscheidet Sieg per Team-Elimination.
                    String err = plugin.getPartyFFAManager()
                            .startTeams(party, kitId, t1, t2);
                    if (err != null) {
                        player.sendMessage(plugin.getConfigManager().prefixed("general.error", "&c{error}", java.util.Map.of("error", err)));
                    } else {
                        plugin.getPartyManager().broadcast(party,
                                "§bTeam vs Team §7started §b" + t1.size() + "v" + t2.size() + "§7.");
                    }
                    player.closeInventory();
                }
            }
        }
    }

    /**
     * Wenn die Edit-Layout-GUI geschlossen wird, Cursor leeren bzw. das auf
     * dem Cursor liegende Item zurück in einen freien Top-Slot legen. Sonst
     * würde Bukkit das Cursor-Item ins Player-Inventar droppen — ein Free-
     * Item-Bug, weil das Item ja eigentlich Bestandteil des Kit-Layouts ist.
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (title == null) return;

        if (title.startsWith(GUIManager.EDIT_LAYOUT_GUI_TITLE_PREFIX)) {
            ItemStack cursor = event.getView().getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                event.getView().setCursor(null);
            }
            player.updateInventory();
        }

        // Kit Builder: clear cursor to prevent item leaking into real inventory
        if (title.startsWith(GUIManager.CUSTOM_KIT_BUILDER_PREFIX)) {
            ItemStack cursor = event.getView().getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                event.getView().setCursor(null);
            }
            player.updateInventory();
        }
    }

    private void handleArmorTrimGUIClick(InventoryClickEvent event, Player player, Inventory top, Inventory clickedInv) {
        event.setCancelled(true);
        if (clickedInv != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        // Close button
        String name = meta.getDisplayName();
        if (name != null && name.contains("§cClose") && event.getRawSlot() == 49) {
            player.closeInventory();
            plugin.getGuiManager().closeGUI(player.getUniqueId());
            return;
        }

        NamespacedKey pieceKey  = plugin.getGuiManager().getArmorTrimPieceKey();
        NamespacedKey actionKey = plugin.getGuiManager().getArmorTrimActionKey();
        if (!pdc.has(pieceKey, PersistentDataType.STRING)
                || !pdc.has(actionKey, PersistentDataType.STRING)) {
            return;
        }
        String pieceStr  = pdc.get(pieceKey,  PersistentDataType.STRING);
        String actionStr = pdc.get(actionKey, PersistentDataType.STRING);

        dev.duels.managers.ArmorTrimManager.Piece piece;
        try {
            piece = dev.duels.managers.ArmorTrimManager.Piece.valueOf(pieceStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return;
        }

        var atm = plugin.getArmorTrimManager();
        UUID uuid = player.getUniqueId();

        boolean shift = event.isShiftClick();
        boolean back  = event.isRightClick();

        int dir = back ? -1 : 1;
        switch (actionStr) {
            case "trim" -> {
                String current = atm.getTrim(uuid, piece);
                String next = shift ? "" : atm.cyclePattern(current, dir);
                atm.setTrim(uuid, piece, next);
            }
            case "material" -> {
                String current = atm.getMaterial(uuid, piece);
                String next = shift ? "" : atm.cycleMaterial(current, dir);
                atm.setMaterial(uuid, piece, next);
            }
            case "clear" -> {
                atm.setTrim(uuid, piece, "");
                atm.setMaterial(uuid, piece, "");
            }
            default -> { return; }
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);

        // GUI neu öffnen damit Anzeige aktualisiert ist
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.getGuiManager().openArmorTrimGUI(player), 1L);
    }
}

