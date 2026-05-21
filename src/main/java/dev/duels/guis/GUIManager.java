package dev.duels.guis;

import dev.duels.DuelsPlugin;
import dev.duels.managers.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class GUIManager {

    private final DuelsPlugin plugin;
    private final Map<UUID, GUI> openGUIs = new HashMap<>();
    private final NamespacedKey duelTargetKey;

    // GUI Titles
    public static final String QUEUE_GUI_TITLE = "§aSelect a Kit §8(Queue)";
    public static final String KITS_GUI_TITLE = "§bAvailable Kits";
    public static final String DUEL_GUI_TITLE = "§aSelect a Kit";
    public static final String BESTOF_GUI_TITLE = "§dSelect Match Length";
    public static final String SETTINGS_GUI_TITLE = "§cSettings";
    public static final String STATS_GUI_TITLE = "§bYour Stats";
    public static final String EDIT_LAYOUTS_GUI_TITLE = "§6Edit Kit Inventory Layouts";
    public static final String EDIT_LAYOUT_GUI_TITLE_PREFIX = "§eEdit Layout: ";
    // Party GUI titles
    public static final String PARTY_MENU_TITLE = "§dParty Menu";
    public static final String PARTY_SELECT_MEMBER_TITLE = "§dPick a Member to Duel";
    public static final String PARTY_TEAMS_TITLE = "§dAssign Teams";
    public static final String PARTY_KIT_SELECT_TITLE = "§dSelect a Kit §8(Party)";

    // PDC keys
    private final NamespacedKey queueKitKey;
    private final NamespacedKey previewKitKey;
    private final NamespacedKey duelKitKey;
    private final NamespacedKey editKitKey;
    private final NamespacedKey bestOfValueKey;
    private final NamespacedKey armorTrimOpenKey;
    private final NamespacedKey armorTrimPieceKey;
    private final NamespacedKey armorTrimActionKey;
    private final NamespacedKey armorTrimLockedKey;

    public GUIManager(DuelsPlugin plugin) {
        this.plugin = plugin;

        this.queueKitKey = new NamespacedKey(plugin, "queue_kit");
        this.previewKitKey = new NamespacedKey(plugin, "preview_kit");
        this.duelKitKey = new NamespacedKey(plugin, "duel_kit");
        this.editKitKey = new NamespacedKey(plugin, "edit_kit");
        this.bestOfValueKey = new NamespacedKey(plugin, "bestof_value");
        this.duelTargetKey = new NamespacedKey(plugin, "duel_target");
        this.armorTrimOpenKey   = new NamespacedKey(plugin, "armortrim_open");
        this.armorTrimPieceKey  = new NamespacedKey(plugin, "armortrim_piece");
        this.armorTrimActionKey = new NamespacedKey(plugin, "armortrim_action");
        this.armorTrimLockedKey = new NamespacedKey(plugin, "armortrim_locked");
    }
    public static final String COMPARE_GUI_TITLE = "§bCompare Stats";

    private enum StatsSort {
        KILLS("Kills"),
        WINS("Wins"),
        KD("KD"),
        WINRATE("Winrate");

        private final String label;
        StatsSort(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final Map<UUID, StatsSort> statsSortMode = new HashMap<>();

    public void openQueueGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, QUEUE_GUI_TITLE);
        populateQueueGUI(player, inv);
        player.openInventory(inv);
        openGUIs.put(player.getUniqueId(), new GUI(QUEUE_GUI_TITLE, System.currentTimeMillis()));
    }

    public void populateQueueGUI(Player viewer, Inventory inv) {
        inv.clear();

        var gc = plugin.getGuiConfig();

        Set<String> kits = plugin.getKitManager().getKitNames();
        if (kits.isEmpty()) {
            ItemStack noKits = gc != null
                    ? gc.buildItem("queue-gui.no-kits", Material.BARRIER, "§cNo Kits Available",
                            Arrays.asList("§7There are no kits available yet.", "§7Ask an admin to create some kits!"))
                    : createItem(Material.BARRIER, "§cNo Kits Available",
                            Arrays.asList("§7There are no kits available yet.", "§7Ask an admin to create some kits!"));
            int slot = gc != null ? gc.getSlot("queue-gui.no-kits", 22) : 22;
            inv.setItem(slot, noKits);
            return;
        }

        // Reihenfolge aus kits.yml beibehalten — KEIN alphabetischer Sort.
        List<String> sortedKits = new ArrayList<>(kits);

        int total = Math.min(sortedKits.size(), 35);
        int startRow = getCenteredStartRow(total, 7, 5);
        int rowsNeeded = (int) Math.ceil(total / 7.0);

        int kitIndex = 0;
        for (int r = 0; r < rowsNeeded; r++) {
            int remaining = total - kitIndex;
            int countThisRow = Math.min(7, remaining);

            int[] cols = centeredColsWithMiddleGap(countThisRow);

            for (int c = 0; c < countThisRow; c++) {
                String kitId = sortedKits.get(kitIndex);
                KitManager.Kit kit = plugin.getKitManager().getKit(kitId);

                Material previewMat = plugin.getKitManager().getKitPreviewMaterial(kitId);

                int queued = plugin.getQueueManager().getQueueSize(kitId);
                int playing = plugin.getQueueManager().getPlayingCount(kitId);
                int sum = queued + playing;
                boolean queuedByViewer = plugin.getQueueManager().isInQueue(viewer.getUniqueId(), kitId);

                ItemStack kitItem = new ItemStack(previewMat);
                kitItem.setAmount(Math.max(1, Math.min(64, sum)));

                ItemMeta meta = kitItem.getItemMeta();
                String display = (kit != null ? kit.getDisplayName() : kitId);
                meta.setDisplayName(display + (queuedByViewer ? " §7(Queued)" : ""));

                meta.setLore(Arrays.asList(
                        "§7In Queue: §a" + queued,
                        "§7Playing: §6" + playing,
                        "",
                        queuedByViewer ? "§cClick to leave queue" : "§eClick to join queue"
                ));

                meta.getPersistentDataContainer().set(queueKitKey, PersistentDataType.STRING, kitId);
                kitItem.setItemMeta(meta);

                int row = startRow + r;
                int col = cols[c];
                int slot = (row * 9) + col;
                inv.setItem(slot, kitItem);

                kitIndex++;
            }
        }

        ItemStack info = gc != null
                ? gc.buildItem("queue-gui.info", Material.PAPER, "§6Queue: Select a Kit",
                        Arrays.asList("§7Pick a kit and you will be queued.", "§7Click again to leave.", "§7Live updates: queue + playing."))
                : createItem(Material.PAPER, "§6Queue: Select a Kit",
                        Arrays.asList("§7Pick a kit and you will be queued.", "§7Click again to leave.", "§7Live updates: queue + playing."));
        inv.setItem(gc != null ? gc.getSlot("queue-gui.info", 4) : 4, info);

        ItemStack close = gc != null
                ? gc.buildItem("queue-gui.close", Material.BARRIER, "§cClose", null)
                : createItem(Material.BARRIER, "§cClose", null);
        inv.setItem(gc != null ? gc.getSlot("queue-gui.close", 49) : 49, close);
    }

    private void populateKitsGUI(Inventory inv) {
        inv.clear();


        var gc = plugin.getGuiConfig();

        Set<String> kits = plugin.getKitManager().getKitNames();
        if (kits == null || kits.isEmpty()) {
            ItemStack noKits = gc != null
                    ? gc.buildItem("kits-gui.no-kits", Material.BARRIER, "§cNo Kits Available",
                            Arrays.asList("§7There are no kits available yet.", "§7Ask an admin to create some kits!"))
                    : createItem(Material.BARRIER, "§cNo Kits Available",
                            Arrays.asList("§7There are no kits available yet.", "§7Ask an admin to create some kits!"));
            inv.setItem(gc != null ? gc.getSlot("kits-gui.no-kits", 22) : 22, noKits);
            return;
        }


        // Reihenfolge aus kits.yml beibehalten — KEIN alphabetischer Sort.
        List<String> sortedKits = new ArrayList<>(kits);


        int total = Math.min(sortedKits.size(), 35);
        int startRow = getCenteredStartRow(total, 7, 5);
        int rowsNeeded = (int) Math.ceil(total / 7.0);


        int kitIndex = 0;
        for (int r = 0; r < rowsNeeded; r++) {
            int remaining = total - kitIndex;
            int countThisRow = Math.min(7, remaining);


            int[] cols = centeredColsWithMiddleGap(countThisRow);


            for (int c = 0; c < countThisRow; c++) {
                String kitId = sortedKits.get(kitIndex++);
                Material previewMat = plugin.getKitManager().getKitPreviewMaterial(kitId);


                ItemStack kitItem = new ItemStack(previewMat);
                ItemMeta meta = kitItem.getItemMeta();


                KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
                meta.setDisplayName(kit != null ? kit.getDisplayName() : kitId);


                int itemCount = kit != null ? kit.getItems().size() : 0;
                meta.setLore(Arrays.asList(
                        "§7Contains " + itemCount + " items",
                        "",
                        "§eClick to preview kit in your inventory"
                ));


                meta.getPersistentDataContainer().set(previewKitKey, PersistentDataType.STRING, kitId);
                kitItem.setItemMeta(meta);


                int row = startRow + r;
                int col = cols[c];
                inv.setItem((row * 9) + col, kitItem);
            }
        }


        ItemStack instructions = gc != null
                ? gc.buildItem("kits-gui.info", Material.PAPER, "§6Select a Kit",
                        Arrays.asList("§7Click on a kit to preview it."))
                : createItem(Material.PAPER, "§6Select a Kit",
                        Arrays.asList("§7Click on a kit to preview it."));
        inv.setItem(gc != null ? gc.getSlot("kits-gui.info", 4) : 4, instructions);


        ItemStack close = gc != null
                ? gc.buildItem("kits-gui.close", Material.BARRIER, "§cClose", null)
                : createItem(Material.BARRIER, "§cClose", null);
        inv.setItem(gc != null ? gc.getSlot("kits-gui.close", 49) : 49, close);
    }

    public void openKitsGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, KITS_GUI_TITLE);
        populateKitsGUI(inv);
        player.openInventory(inv);
        openGUIs.put(player.getUniqueId(), new GUI(KITS_GUI_TITLE, System.currentTimeMillis()));
    }



    public void openDuelGUI(Player sender, Player target) {
        Inventory inv = Bukkit.createInventory(null, 54, DUEL_GUI_TITLE);
        populateDuelGUI(sender, target, inv);
        sender.openInventory(inv);
        openGUIs.put(sender.getUniqueId(), new GUI(DUEL_GUI_TITLE, System.currentTimeMillis()));
    }

    private void populateDuelGUI(Player sender, Player target, Inventory inv) {
        inv.clear();

        Set<String> kits = plugin.getKitManager().getKitNames();
        if (kits.isEmpty()) {
            sender.sendMessage(plugin.getPrefix() + "§cNo kits available! Ask an admin to create one.");
            return;
        }

        // Reihenfolge aus kits.yml beibehalten — KEIN alphabetischer Sort.
        List<String> sortedKits = new ArrayList<>(kits);

        int total = Math.min(sortedKits.size(), 35);
        int startRow = getCenteredStartRow(total, 7, 5);
        int rowsNeeded = (int) Math.ceil(total / 7.0);

        int kitIndex = 0;
        for (int r = 0; r < rowsNeeded; r++) {
            int remaining = total - kitIndex;
            int countThisRow = Math.min(7, remaining);

            int[] cols = centeredColsWithMiddleGap(countThisRow);

            for (int c = 0; c < countThisRow; c++) {
                String kitId = sortedKits.get(kitIndex);
                Material previewMat = plugin.getKitManager().getKitPreviewMaterial(kitId);

                ItemStack kitItem = new ItemStack(previewMat);
                ItemMeta meta = kitItem.getItemMeta();

                KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
                String display = kit != null ? kit.getDisplayName() : kitId;
                meta.setDisplayName(display);

                int itemCount = kit != null ? kit.getItems().size() : 0;
                int defaultBestOf = plugin.getConfigManager().getMainConfig().getInt("default-bestof", 1);

                meta.setLore(Arrays.asList(
                        "§7Challenge §c" + target.getName() + " §7with this kit",
                        "§7Contains " + itemCount + " items",
                        "",
                        "§aLeft-Click §7= send request §8(Best of " + defaultBestOf + ")",
                        "§eRight-Click §7= choose Best-Of"
                ));

                meta.getPersistentDataContainer().set(duelKitKey, PersistentDataType.STRING, kitId);
                kitItem.setItemMeta(meta);

                int row = startRow + r;
                int col = cols[c];
                int slot = (row * 9) + col;
                inv.setItem(slot, kitItem);

                kitIndex++;
            }
        }

        ItemStack info = createItem(Material.PAPER, "§6Select a Kit",
                Arrays.asList("§7Choose a kit to challenge", "§7" + target.getName() + " with.", "", "§eLeft=send  §eRight=best-of"));

        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.getPersistentDataContainer().set(duelTargetKey, PersistentDataType.STRING, target.getUniqueId().toString());
        info.setItemMeta(infoMeta);

        inv.setItem(4, info);
    }

    public void openBestOfGUI(Player sender, Player target, String kitId) {
        Inventory inv = Bukkit.createInventory(null, 27, BESTOF_GUI_TITLE);

        KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
        String display = kit != null ? kit.getDisplayName() : kitId;

        List<Integer> options = plugin.getConfigManager().getMainConfig().getIntegerList("bestof-options");
        if (options == null || options.isEmpty()) options = Arrays.asList(1, 3, 5);

        int slot = 10;
        for (int bestOf : options) {
            if (slot >= 16) break;

            ItemStack item = createItem(Material.PAPER, "§eFirst to " + bestOf,
                    Arrays.asList("§7Kit: §b" + display, "§7Target: §c" + target.getName(), "", "§eClick to choose"));

            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(bestOfValueKey, PersistentDataType.INTEGER, bestOf);
            meta.getPersistentDataContainer().set(duelKitKey, PersistentDataType.STRING, kitId);
            meta.getPersistentDataContainer().set(duelTargetKey, PersistentDataType.STRING, target.getUniqueId().toString());
            item.setItemMeta(meta);


            inv.setItem(slot, item);
            slot++;
        }

        // Custom-Button: schließt GUI und startet Chat-Input für 1-100.
        ItemStack custom = createItem(Material.ANVIL, "§6Custom...",
                Arrays.asList("§7Type a number §f1-100 §7in chat.", "§7Type §ccancel §7to abort.", "", "§eClick to enter custom value"));
        ItemMeta cm = custom.getItemMeta();
        cm.getPersistentDataContainer().set(duelKitKey, PersistentDataType.STRING, kitId);
        cm.getPersistentDataContainer().set(duelTargetKey, PersistentDataType.STRING, target.getUniqueId().toString());
        custom.setItemMeta(cm);
        inv.setItem(16, custom);

        ItemStack back = createItem(Material.ARROW, "§cBack", null);
        inv.setItem(18, back);

        ItemStack close = createItem(Material.BARRIER, "§cClose", null);
        inv.setItem(26, close);

        sender.openInventory(inv);
        openGUIs.put(sender.getUniqueId(), new GUI(BESTOF_GUI_TITLE, System.currentTimeMillis()));
    }

    /** Wartet auf eine Chat-Zahl (1-100) für Custom-FirstTo. */
    private final java.util.Map<UUID, PendingCustomBestOf> pendingCustomBestOf = new java.util.HashMap<>();

    public static class PendingCustomBestOf {
        public final UUID target;
        public final String kitId;
        public PendingCustomBestOf(UUID target, String kitId) { this.target = target; this.kitId = kitId; }
    }

    public void beginCustomBestOfPrompt(Player sender, UUID target, String kitId) {
        pendingCustomBestOf.put(sender.getUniqueId(), new PendingCustomBestOf(target, kitId));
        sender.sendMessage(plugin.getPrefix() + "§eType a number §f1-100 §ein chat to set the round count, or §ccancel§e.");
    }

    public PendingCustomBestOf consumePendingCustomBestOf(UUID player) {
        return pendingCustomBestOf.remove(player);
    }

    public PendingCustomBestOf peekPendingCustomBestOf(UUID player) {
        return pendingCustomBestOf.get(player);
    }

    public void openSettingsGUI(Player player) {
        var gc = plugin.getGuiConfig();
        Inventory inv = Bukkit.createInventory(null, 27, SETTINGS_GUI_TITLE);

        ItemStack editLayouts = gc != null
                ? gc.buildItem("settings-gui.edit-layouts", Material.CHEST, "§aEdit Kit Inventory Layouts",
                        Arrays.asList("§7Edit your personal inventory layout", "§7for each kit separately.", "", "§eClick to edit"))
                : createItem(Material.CHEST, "§aEdit Kit Inventory Layouts",
                        Arrays.asList("§7Edit your personal inventory layout", "§7for each kit separately.", "", "§eClick to edit"));
        inv.setItem(gc != null ? gc.getSlot("settings-gui.edit-layouts", 11) : 11, editLayouts);

        ItemStack autoFly = createAutoFlyItem(player);
        inv.setItem(gc != null ? gc.getSlot("settings-gui.auto-fly-name", 15) : 15, autoFly);

        ItemStack close = gc != null
                ? gc.buildItem("settings-gui.close", Material.BARRIER, "§cClose", null)
                : createItem(Material.BARRIER, "§cClose", null);
        inv.setItem(gc != null ? gc.getSlot("settings-gui.close", 26) : 26, close);

        player.openInventory(inv);
        openGUIs.put(player.getUniqueId(), new GUI(SETTINGS_GUI_TITLE, System.currentTimeMillis()));
    }

    public ItemStack createAutoFlyItem(Player player) {
        boolean enabled = plugin.getPlayerManager().getAutoFly(player.getUniqueId());
        Material material = enabled ? Material.LIME_DYE : Material.RED_DYE;

        return createItem(material, "§bAuto Fly",
                Arrays.asList("§7Automatically enable fly in lobby.", "",
                        "§7Status: " + (enabled ? "§aENABLED" : "§cDISABLED"), "", "§eClick to toggle"));
    }

    public void openEditLayoutsGUI(Player player) {
        var gc = plugin.getGuiConfig();
        Inventory inv = Bukkit.createInventory(null, 54, EDIT_LAYOUTS_GUI_TITLE);

        Set<String> kits = plugin.getKitManager().getKitNames();
        if (kits.isEmpty()) {
            ItemStack noKits = gc != null
                    ? gc.buildItem("edit-layouts-gui.no-kits", Material.BARRIER, "§cNo Kits Available",
                            Arrays.asList("§7There are no kits available yet.", "§7Ask an admin to create some kits!"))
                    : createItem(Material.BARRIER, "§cNo Kits Available",
                            Arrays.asList("§7There are no kits available yet.", "§7Ask an admin to create some kits!"));
            inv.setItem(gc != null ? gc.getSlot("edit-layouts-gui.no-kits", 22) : 22, noKits);
        } else {
            // Reihenfolge aus kits.yml beibehalten — KEIN alphabetischer Sort.
            List<String> sortedKits = new ArrayList<>(kits);

            int total = Math.min(sortedKits.size(), 35);
            int startRow = getCenteredStartRow(total, 7, 5);
            int rowsNeeded = (int) Math.ceil(total / 7.0);

            int kitIndex = 0;
            for (int r = 0; r < rowsNeeded; r++) {
                int remaining = total - kitIndex;
                int countThisRow = Math.min(7, remaining);

                int[] cols = centeredColsWithMiddleGap(countThisRow);

                for (int c = 0; c < countThisRow; c++) {
                    String kitId = sortedKits.get(kitIndex);
                    KitManager.Kit kit = plugin.getKitManager().getKit(kitId);

                    Material previewMat = plugin.getKitManager().getKitPreviewMaterial(kitId);

                    ItemStack kitItem = new ItemStack(previewMat);
                    ItemMeta meta = kitItem.getItemMeta();

                    meta.setDisplayName(kit != null ? kit.getDisplayName() : kitId);

                    boolean hasCustomLayout = plugin.getKitManager().getCustomLayout(player.getUniqueId(), kitId) != null;

                    meta.getPersistentDataContainer().set(editKitKey, PersistentDataType.STRING, kitId);

                    meta.setLore(Arrays.asList(
                            "§7Edit your inventory layout for this kit.",
                            "",
                            hasCustomLayout ? "§a✓ Custom layout saved" : "§7No custom layout yet",
                            "",
                            "§eClick to edit layout"
                    ));

                    kitItem.setItemMeta(meta);

                    int row = startRow + r;
                    int col = cols[c];
                    int slot = (row * 9) + col;
                    inv.setItem(slot, kitItem);

                    kitIndex++;
                }
            }
        }

        ItemStack instructions = gc != null
                ? gc.buildItem("edit-layouts-gui.info", Material.PAPER, "§6Select a Kit to Edit",
                        Arrays.asList("§7Click on a kit to §eedit §7your", "§apersonal inventory layout §7for it."))
                : createItem(Material.PAPER, "§6Select a Kit to Edit",
                        Arrays.asList("§7Click on a kit to §eedit §7your", "§apersonal inventory layout §7for it."));
        inv.setItem(gc != null ? gc.getSlot("edit-layouts-gui.info", 4) : 4, instructions);

        ItemStack close = gc != null
                ? gc.buildItem("edit-layouts-gui.close", Material.BARRIER, "§cClose", null)
                : createItem(Material.BARRIER, "§cClose", null);
        inv.setItem(gc != null ? gc.getSlot("edit-layouts-gui.close", 49) : 49, close);

        player.openInventory(inv);
        openGUIs.put(player.getUniqueId(), new GUI(EDIT_LAYOUTS_GUI_TITLE, System.currentTimeMillis()));
    }

    public void openEditLayoutGUI(Player player, String kitId) {
        KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
        String display = kit != null ? kit.getDisplayName() : kitId;

        Inventory inv = Bukkit.createInventory(null, 54, EDIT_LAYOUT_GUI_TITLE_PREFIX + display);

        ItemStack[] customLayout = plugin.getKitManager().getCustomLayout(player.getUniqueId(), kitId);

        // WICHTIG: Wenn CustomLayout existiert -> NUR custom benutzen, kein Fallback aufs Kit
        if (customLayout != null) {
            for (int i = 0; i < 36; i++) {
                ItemStack it = (i < customLayout.length ? customLayout[i] : null);
                inv.setItem(i, it == null ? null : it.clone());
            }
        } else {
            // Nur wenn noch kein CustomLayout existiert -> Kit Default reinladen
            for (int i = 0; i < 36; i++) {
                ItemStack it = (kit != null ? kit.getItem(i) : null);
                inv.setItem(i, it == null ? null : it.clone());
            }
        }

        var gc = plugin.getGuiConfig();

        // --- Armor/Offhand usw. bleibt wie bei dir ---
        ItemStack redGlass = gc != null
                ? gc.buildItem("edit-layout-gui.fixed-slot", Material.RED_STAINED_GLASS_PANE, "§cFixed Slot",
                        Arrays.asList("§7This slot is fixed by the kit.", "§7You cannot change armor/offhand layout."))
                : createItem(Material.RED_STAINED_GLASS_PANE, "§cFixed Slot",
                        Arrays.asList("§7This slot is fixed by the kit.", "§7You cannot change armor/offhand layout."));

        inv.setItem(36, kit != null && kit.getItem(100) != null ? kit.getItem(100).clone() : redGlass.clone());
        inv.setItem(37, kit != null && kit.getItem(101) != null ? kit.getItem(101).clone() : redGlass.clone());
        inv.setItem(38, kit != null && kit.getItem(102) != null ? kit.getItem(102).clone() : redGlass.clone());
        inv.setItem(39, kit != null && kit.getItem(103) != null ? kit.getItem(103).clone() : redGlass.clone());
        inv.setItem(40, kit != null && kit.getItem(99)  != null ? kit.getItem(99).clone()  : redGlass.clone());

        ItemStack info = gc != null
                ? gc.buildItem("edit-layout-gui.info", Material.PAPER, "§6Editing Layout",
                        Arrays.asList("§7Move items in slots §a0-35§7.", "§7Armor/offhand and buttons are locked.", "", "§eClick Save when done."))
                : createItem(Material.PAPER, "§6Editing Layout",
                        Arrays.asList("§7Move items in slots §a0-35§7.", "§7Armor/offhand and buttons are locked.", "", "§eClick Save when done."));
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.getPersistentDataContainer().set(editKitKey, PersistentDataType.STRING, kitId);
        info.setItemMeta(infoMeta);
        inv.setItem(gc != null ? gc.getSlot("edit-layout-gui.info", 45) : 45, info);

        ItemStack reset = gc != null
                ? gc.buildItem("edit-layout-gui.reset", Material.RED_DYE, "§cReset to Default",
                        Arrays.asList("§7Reset your inventory layout", "§7back to the default arrangement."))
                : createItem(Material.RED_DYE, "§cReset to Default",
                        Arrays.asList("§7Reset your inventory layout", "§7back to the default arrangement."));
        inv.setItem(gc != null ? gc.getSlot("edit-layout-gui.reset", 51) : 51, tagKitId(reset, kitId));

        ItemStack save = gc != null
                ? gc.buildItem("edit-layout-gui.save", Material.LIME_DYE, "§aSave Layout",
                        Arrays.asList("§7Save your current inventory arrangement", "§7as your personal layout for this kit."))
                : createItem(Material.LIME_DYE, "§aSave Layout",
                        Arrays.asList("§7Save your current inventory arrangement", "§7as your personal layout for this kit."));
        inv.setItem(gc != null ? gc.getSlot("edit-layout-gui.save", 52) : 52, tagKitId(save, kitId));

        ItemStack close = gc != null
                ? gc.buildItem("edit-layout-gui.close", Material.BARRIER, "§cClose", Arrays.asList("§7Close without saving"))
                : createItem(Material.BARRIER, "§cClose", Arrays.asList("§7Close without saving"));
        inv.setItem(gc != null ? gc.getSlot("edit-layout-gui.close", 53) : 53, tagKitId(close, kitId));

        // Armor-Trim-Button — IMMER sichtbar. Ohne Permission ist er
        // sichtbar aber unbenutzbar (Lore weist darauf hin, Klick passiert
        // nichts).
        boolean hasTrimPerm = player.hasPermission(dev.duels.managers.ArmorTrimManager.PERMISSION);
        ItemStack trimBtn;
        if (hasTrimPerm) {
            trimBtn = createItem(Material.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE, "§dArmor Trims",
                    Arrays.asList("§7Customize the §dArmor Trim",
                            "§7applied to your duel armor.", "",
                            "§eClick to open editor"));
        } else {
            trimBtn = createItem(Material.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE, "§7Armor Trims §c(locked)",
                    Arrays.asList("§7Customize the §dArmor Trim",
                            "§7applied to your duel armor.", "",
                            "§cYou don't have permission",
                            "§cto use this feature."));
        }
        ItemMeta tmeta = trimBtn.getItemMeta();
        tmeta.getPersistentDataContainer().set(armorTrimOpenKey, PersistentDataType.STRING, kitId);
        if (!hasTrimPerm) {
            // Markiert den Button als "locked" → Klick-Handler verweigert
            tmeta.getPersistentDataContainer().set(armorTrimLockedKey, PersistentDataType.BYTE, (byte) 1);
        }
        trimBtn.setItemMeta(tmeta);
        inv.setItem(50, trimBtn);

        player.openInventory(inv);
        openGUIs.put(player.getUniqueId(), new GUI(EDIT_LAYOUT_GUI_TITLE_PREFIX + display, System.currentTimeMillis()));
    }

    public static final String ARMOR_TRIM_GUI_TITLE = "§dArmor Trim Editor";

    public NamespacedKey getArmorTrimOpenKey()   { return armorTrimOpenKey; }
    public NamespacedKey getArmorTrimPieceKey()  { return armorTrimPieceKey; }
    public NamespacedKey getArmorTrimActionKey() { return armorTrimActionKey; }
    public NamespacedKey getArmorTrimLockedKey() { return armorTrimLockedKey; }

    /**
     * Öffnet den Armor-Trim-Editor: pro Rüstungsteil (Helm, Chest,
     * Leggings, Boots) eine Zeile mit dem aktuellen Trim-Pattern, dem
     * Material und einer Remove-Option.
     */
    public void openArmorTrimGUI(Player player) {
        if (!player.hasPermission(dev.duels.managers.ArmorTrimManager.PERMISSION)) {
            player.sendMessage(plugin.getPrefix() + "§cYou don't have permission to edit armor trims.");
            return;
        }
        Inventory inv = Bukkit.createInventory(null, 54, ARMOR_TRIM_GUI_TITLE);
        UUID uuid = player.getUniqueId();
        var atm = plugin.getArmorTrimManager();

        // Header
        ItemStack title = createItem(Material.NETHERITE_INGOT, "§dArmor Trim Editor",
                Arrays.asList("§7Click trim/material to cycle.",
                        "§7Click §cRemove §7to clear that piece."));
        inv.setItem(4, title);

        // 4 Rüstungs-Zeilen ab Slot 19
        dev.duels.managers.ArmorTrimManager.Piece[] pieces = {
                dev.duels.managers.ArmorTrimManager.Piece.HELMET,
                dev.duels.managers.ArmorTrimManager.Piece.CHESTPLATE,
                dev.duels.managers.ArmorTrimManager.Piece.LEGGINGS,
                dev.duels.managers.ArmorTrimManager.Piece.BOOTS
        };
        Material[] pieceMats = {
                Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS
        };

        int[] rowStarts = { 10, 19, 28, 37 };
        for (int i = 0; i < pieces.length; i++) {
            var piece = pieces[i];
            String currentTrim = atm.getTrim(uuid, piece);
            String currentMat  = atm.getMaterial(uuid, piece);

            // Piece-Vorschau (Slot rowStart) — Netherite-Rüstung mit
            // aktuellem Trim live drauf, damit man die Optik sieht.
            ItemStack pieceItem = new ItemStack(pieceMats[i]);
            ItemMeta pmRaw = pieceItem.getItemMeta();
            if (pmRaw instanceof org.bukkit.inventory.meta.ArmorMeta am) {
                org.bukkit.inventory.meta.trim.TrimPattern tp =
                        dev.duels.managers.ArmorTrimManager.resolvePattern(currentTrim);
                org.bukkit.inventory.meta.trim.TrimMaterial tm =
                        dev.duels.managers.ArmorTrimManager.resolveMaterial(currentMat);
                if (tp != null && tm != null) {
                    am.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(tm, tp));
                }
            }
            pmRaw.setDisplayName("§b" + dev.duels.managers.ArmorTrimManager.displayName(piece.key()));
            pmRaw.setLore(Arrays.asList(
                    "§7Trim: §f" + (currentTrim.isEmpty() ? "§7None" : currentTrim),
                    "§7Material: §f" + (currentMat.isEmpty() ? "§7None" : currentMat)
            ));
            pieceItem.setItemMeta(pmRaw);
            inv.setItem(rowStarts[i], pieceItem);

            // Trim cycle button — Item = das Smithing-Template des aktuellen
            // Patterns (visuell durch-cyclen). Wenn kein Pattern: Flow als
            // neutrales Default-Icon.
            ItemStack trimBtn = createItem(trimTemplateMaterial(currentTrim),
                    "§eTrim: §f" + (currentTrim.isEmpty() ? "§7None"
                            : dev.duels.managers.ArmorTrimManager.displayName(currentTrim)),
                    Arrays.asList("§7Left-click to cycle forward",
                            "§7Right-click to cycle backward",
                            "§7Shift-click: jump to None"));
            tagPieceAction(trimBtn, piece, "trim");
            inv.setItem(rowStarts[i] + 2, trimBtn);

            // Material cycle button — Icon = das jeweilige Material-Item.
            ItemStack matBtn = createItem(materialIconFor(currentMat),
                    "§eMaterial: §f" + (currentMat.isEmpty() ? "§7None"
                            : dev.duels.managers.ArmorTrimManager.displayName(currentMat)),
                    Arrays.asList("§7Left-click to cycle forward",
                            "§7Right-click to cycle backward",
                            "§7Shift-click: jump to None"));
            tagPieceAction(matBtn, piece, "material");
            inv.setItem(rowStarts[i] + 4, matBtn);

            // Remove button (clear both)
            ItemStack remove = createItem(Material.BARRIER, "§cRemove Trim",
                    Arrays.asList("§7Clear trim & material for this piece."));
            tagPieceAction(remove, piece, "clear");
            inv.setItem(rowStarts[i] + 6, remove);
        }

        // Close button
        ItemStack close = createItem(Material.BARRIER, "§cClose", null);
        inv.setItem(49, close);

        player.openInventory(inv);
        openGUIs.put(player.getUniqueId(), new GUI(ARMOR_TRIM_GUI_TITLE, System.currentTimeMillis()));
    }

    private void tagPieceAction(ItemStack item, dev.duels.managers.ArmorTrimManager.Piece piece, String action) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(armorTrimPieceKey, PersistentDataType.STRING, piece.key());
        meta.getPersistentDataContainer().set(armorTrimActionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
    }

    private Material trimTemplateMaterial(String trim) {
        if (trim == null || trim.isEmpty()) return Material.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE;
        String enumName = trim.toUpperCase() + "_ARMOR_TRIM_SMITHING_TEMPLATE";
        try {
            Material m = Material.valueOf(enumName);
            return m;
        } catch (IllegalArgumentException ex) {
            return Material.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE;
        }
    }

    private Material materialIconFor(String material) {
        if (material == null || material.isEmpty()) return Material.GRAY_DYE;
        return switch (material.toLowerCase()) {
            case "amethyst"  -> Material.AMETHYST_SHARD;
            case "copper"    -> Material.COPPER_INGOT;
            case "diamond"   -> Material.DIAMOND;
            case "emerald"   -> Material.EMERALD;
            case "gold"      -> Material.GOLD_INGOT;
            case "iron"      -> Material.IRON_INGOT;
            case "lapis"     -> Material.LAPIS_LAZULI;
            case "netherite" -> Material.NETHERITE_INGOT;
            case "quartz"    -> Material.QUARTZ;
            case "redstone"  -> Material.REDSTONE;
            case "resin"     -> Material.RESIN_BRICK;
            default          -> Material.GRAY_DYE;
        };
    }

    private ItemStack tagKitId(ItemStack item, String kitId) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(editKitKey, PersistentDataType.STRING, kitId);
        item.setItemMeta(meta);
        return item;
    }

    public void openStatsGUI(Player player) {
        StatsSort sort = statsSortMode.getOrDefault(player.getUniqueId(), StatsSort.KILLS);

        Inventory inv = Bukkit.createInventory(null, 27, STATS_GUI_TITLE);

        // Center: your head
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta headMeta = (SkullMeta) head.getItemMeta();
        headMeta.setOwningPlayer(player);
        headMeta.setDisplayName("§a" + player.getName());

        int kills = plugin.getPlayerManager().getStat(player.getUniqueId(), "kills");
        int deaths = plugin.getPlayerManager().getStat(player.getUniqueId(), "deaths");
        int wins = plugin.getPlayerManager().getStat(player.getUniqueId(), "wins");
        int losses = plugin.getPlayerManager().getStat(player.getUniqueId(), "losses");
        double kd = deaths == 0 ? kills : (double) kills / deaths;
        String winrate = plugin.getPlayerManager().calculateWinrate(wins, losses);

        headMeta.setLore(Arrays.asList(
                "§7Kills: §b" + kills,
                "§7Deaths: §c" + deaths,
                "§7KD Ratio: §6" + String.format("%.2f", kd),
                "§7Wins: §a" + wins,
                "§7Losses: §c" + losses,
                "§7Win Rate: §b" + winrate
        ));
        head.setItemMeta(headMeta);
        inv.setItem(13, head);

        // Slot 11: Top 5 paper
        inv.setItem(11, createTop5Item(player, sort));

        // Slot 15: Search Players (sign)
        ItemStack search = createItem(Material.OAK_SIGN, "§eSearch Players",
                Arrays.asList("§7Search a player by name", "§7and compare stats.", "", "§eClick to search"));
        inv.setItem(15, search);

        // Close
        ItemStack close = createItem(Material.BARRIER, "§cClose", null);
        inv.setItem(26, close);

        player.openInventory(inv);
        openGUIs.put(player.getUniqueId(), new GUI(STATS_GUI_TITLE, System.currentTimeMillis()));
    }
    private ItemStack createTop5Item(Player viewer, StatsSort sort) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Sorted: §b" + sort.label() + " §7(click to change)");
        lore.add("");

        List<dev.duels.objects.PlayerData> all = plugin.getPlayerManager().getAllPlayerDataSnapshot();

        all.sort((a, b) -> {
            double av = getSortValue(a, sort);
            double bv = getSortValue(b, sort);
            int cmp = Double.compare(bv, av); // desc
            if (cmp != 0) return cmp;

            String an = a.getName() == null ? "" : a.getName();
            String bn = b.getName() == null ? "" : b.getName();
            return an.compareToIgnoreCase(bn);
        });

        int shown = 0;
        for (dev.duels.objects.PlayerData pd : all) {
            if (pd == null) continue;
            String name = pd.getName() == null ? "Unknown" : pd.getName();

            // skip unknown names if you want
            if (name.equalsIgnoreCase("Unknown")) continue;

            shown++;
            lore.add("§7#" + shown + ": §f" + name + " §8- §a" + formatSortValue(pd, sort));
            if (shown >= 5) break;
        }

        if (shown == 0) {
            lore.add("§7No data yet.");
        }

        return createItem(Material.PAPER, "§6Top 5 Players", lore);
    }

    private double getSortValue(dev.duels.objects.PlayerData pd, StatsSort sort) {
        int kills = pd.getKills();
        int deaths = pd.getDeaths();
        int wins = pd.getWins();
        int losses = pd.getLosses();

        return switch (sort) {
            case KILLS -> kills;
            case WINS -> wins;
            case KD -> deaths == 0 ? kills : (double) kills / deaths;
            case WINRATE -> {
                int total = wins + losses;
                yield total == 0 ? 0.0 : ((double) wins / total) * 100.0;
            }
        };
    }

    private String formatSortValue(dev.duels.objects.PlayerData pd, StatsSort sort) {
        int kills = pd.getKills();
        int deaths = pd.getDeaths();
        int wins = pd.getWins();
        int losses = pd.getLosses();

        return switch (sort) {
            case KILLS -> String.valueOf(kills);
            case WINS -> String.valueOf(wins);
            case KD -> String.format("%.2f", deaths == 0 ? (double) kills : (double) kills / deaths);
            case WINRATE -> {
                int total = wins + losses;
                double wr = total == 0 ? 0.0 : ((double) wins / total) * 100.0;
                yield String.format("%.1f%%", wr);
            }
        };
    }

    private StatsSort nextSort(StatsSort current) {
        StatsSort[] vals = StatsSort.values();
        int i = (current.ordinal() + 1) % vals.length;
        return vals[i];
    }

    public void openCompareGUI(Player viewer, UUID targetUuid) {
        Player target = Bukkit.getPlayer(targetUuid);
        String targetName = (target != null ? target.getName() : plugin.getPlayerManager().getPlayerData(targetUuid).getName());
        if (targetName == null) targetName = "Unknown";

        Inventory inv = Bukkit.createInventory(null, 27, COMPARE_GUI_TITLE);

        ItemStack yours = buildCompareHead(viewer.getUniqueId(), viewer.getName(), true);
        ItemStack theirs = buildCompareHead(targetUuid, targetName, false);

        inv.setItem(11, yours);
        inv.setItem(13, createItem(Material.PAPER, "§bComparison",
                Arrays.asList("§7Left: §aYou", "§7Right: §c" + targetName)));
        inv.setItem(15, theirs);

        inv.setItem(26, createItem(Material.BARRIER, "§cClose", null));

        viewer.openInventory(inv);
        openGUIs.put(viewer.getUniqueId(), new GUI(COMPARE_GUI_TITLE, System.currentTimeMillis()));
    }

    private ItemStack buildCompareHead(UUID uuid, String name, boolean self) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) meta.setOwningPlayer(online);

        meta.setDisplayName((self ? "§a" : "§c") + name);

        int kills = plugin.getPlayerManager().getStat(uuid, "kills");
        int deaths = plugin.getPlayerManager().getStat(uuid, "deaths");
        int wins = plugin.getPlayerManager().getStat(uuid, "wins");
        int losses = plugin.getPlayerManager().getStat(uuid, "losses");
        double kd = deaths == 0 ? kills : (double) kills / deaths;
        String winrate = plugin.getPlayerManager().calculateWinrate(wins, losses);

        meta.setLore(Arrays.asList(
                "§7Kills: §b" + kills,
                "§7Deaths: §c" + deaths,
                "§7KD: §6" + String.format("%.2f", kd),
                "§7Wins: §a" + wins,
                "§7Losses: §c" + losses,
                "§7Winrate: §b" + winrate
        ));

        head.setItemMeta(meta);
        return head;
    }
    public void cycleStatsSortAndReopen(Player player) {
        UUID uuid = player.getUniqueId();
        StatsSort current = statsSortMode.getOrDefault(uuid, StatsSort.KILLS);
        StatsSort next = nextSort(current);
        statsSortMode.put(uuid, next);

        openStatsGUI(player);
    }



    public void updateOpenGUIs() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory() == null) continue;

            String titleStripped = ChatColor.stripColor(player.getOpenInventory().getTitle());
            GUI gui = openGUIs.get(player.getUniqueId());

            if (gui != null && System.currentTimeMillis() - gui.getOpenTime() > 1000) {
                if (titleStripped.equals(ChatColor.stripColor(QUEUE_GUI_TITLE))) {
                    populateQueueGUI(player, player.getOpenInventory().getTopInventory());
                }
            }
        }
    }

    public void refreshQueueGUIs() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory() == null) continue;

            String title = player.getOpenInventory().getTitle();
            if (title.equals(QUEUE_GUI_TITLE)) {
                populateQueueGUI(player, player.getOpenInventory().getTopInventory());
            }
        }
    }

    public void closeGUI(UUID playerId) {
        openGUIs.remove(playerId);
    }

    // ------------------ Party GUIs ------------------

    /** PDC key für die Party-Menü-Actions (DUEL_ONE, FFA, TEAMS, PUBLIC, DISBAND). */
    private final org.bukkit.NamespacedKey partyActionKey =
            new org.bukkit.NamespacedKey(DuelsPlugin.getInstance(), "party_menu_action");
    /** PDC key für Member-UUID beim "Pick a Member" / "Assign Teams" GUI. */
    private final org.bukkit.NamespacedKey partyMemberKey =
            new org.bukkit.NamespacedKey(DuelsPlugin.getInstance(), "party_menu_member");
    /** PDC key für Kit-ID im Party-Kit-Selector. */
    private final org.bukkit.NamespacedKey partyKitKey =
            new org.bukkit.NamespacedKey(DuelsPlugin.getInstance(), "party_menu_kit");
    /** PDC key für vorgemerkten Mode (welche Aktion die Kit-Auswahl anstößt). */
    private final org.bukkit.NamespacedKey partyPendingActionKey =
            new org.bukkit.NamespacedKey(DuelsPlugin.getInstance(), "party_pending_action");

    public org.bukkit.NamespacedKey getPartyActionKey() { return partyActionKey; }
    public org.bukkit.NamespacedKey getPartyMemberKey() { return partyMemberKey; }
    public org.bukkit.NamespacedKey getPartyKitKey() { return partyKitKey; }
    public org.bukkit.NamespacedKey getPartyPendingActionKey() { return partyPendingActionKey; }

    /** Haupt-Party-Menü (nur für Leader). */
    public void openPartyMenu(Player leader) {
        dev.duels.objects.Party party = plugin.getPartyManager().getPartyByLeader(leader.getUniqueId());
        if (party == null) {
            leader.sendMessage(plugin.getPrefix() + "§cYou are not a party leader.");
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 27, PARTY_MENU_TITLE);

        inv.setItem(10, partyActionItem(Material.DIAMOND_SWORD, "§aDuel one from party",
                Arrays.asList("§7Pick a single party member", "§7and start a 1v1 duel."),
                "DUEL_ONE"));

        inv.setItem(12, partyActionItem(Material.CROSSBOW, "§6Free for All",
                Arrays.asList(
                        "§7All party members spawn at the",
                        "§7configured FFA spawn and fight",
                        "§7on one map. §fLast one alive wins.",
                        "§8Dead players go into spectator."),
                "FFA"));

        inv.setItem(14, partyActionItem(Material.SHIELD, "§bTeam 1 vs Team 2",
                Arrays.asList("§7Pick who is in which team,", "§7then pair them up."),
                "TEAMS"));

        String publicLabel = party.isPublic() ? "§cDisable Public Party" : "§aMake Party Public";
        inv.setItem(16, partyActionItem(Material.BEACON, publicLabel,
                Arrays.asList("§7Public parties appear in chat", "§7and anyone can join."),
                "PUBLIC"));

        inv.setItem(22, partyActionItem(Material.BARRIER, "§cDisband Party", null, "DISBAND"));
        inv.setItem(26, createItem(Material.ARROW, "§7Close", null));

        leader.openInventory(inv);
        openGUIs.put(leader.getUniqueId(), new GUI(PARTY_MENU_TITLE, System.currentTimeMillis()));
    }

    private ItemStack partyActionItem(Material mat, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        meta.getPersistentDataContainer().set(partyActionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    /** GUI zum Auswählen eines Gegners aus der Party (für DUEL_ONE). */
    public void openPartyMemberSelect(Player leader, String pendingAction) {
        dev.duels.objects.Party party = plugin.getPartyManager().getPartyByLeader(leader.getUniqueId());
        if (party == null) return;

        Inventory inv = Bukkit.createInventory(null, 54, PARTY_SELECT_MEMBER_TITLE);

        int slot = 10;
        for (UUID memberId : party.getMembers()) {
            if (memberId.equals(leader.getUniqueId())) continue;
            Player m = Bukkit.getPlayer(memberId);
            if (m == null || !m.isOnline()) continue;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(m);
            meta.setDisplayName("§e" + m.getName());
            meta.setLore(Arrays.asList("§7Click to start a 1v1 duel."));
            meta.getPersistentDataContainer().set(partyMemberKey, PersistentDataType.STRING, memberId.toString());
            meta.getPersistentDataContainer().set(partyPendingActionKey, PersistentDataType.STRING, pendingAction);
            head.setItemMeta(meta);

            inv.setItem(slot, head);
            slot++;
            if (slot % 9 == 8) slot += 2;
            if (slot >= 44) break;
        }

        inv.setItem(49, createItem(Material.BARRIER, "§cClose", null));

        leader.openInventory(inv);
        openGUIs.put(leader.getUniqueId(), new GUI(PARTY_SELECT_MEMBER_TITLE, System.currentTimeMillis()));
    }

    /** Team-Auswahl-GUI (Klick auf Kopf → Team 1/2 togglen). */
    public void openPartyTeamsGUI(Player leader) {
        dev.duels.objects.Party party = plugin.getPartyManager().getPartyByLeader(leader.getUniqueId());
        if (party == null) return;

        Inventory inv = Bukkit.createInventory(null, 54, PARTY_TEAMS_TITLE);

        int slot = 10;
        for (UUID memberId : party.getMembers()) {
            Player m = Bukkit.getPlayer(memberId);
            if (m == null || !m.isOnline()) continue;

            int team = party.getTeam(memberId);
            String teamLabel = team == 1 ? " §9(Team 1)" : team == 2 ? " §c(Team 2)" : " §7(unassigned)";

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(m);
            meta.setDisplayName("§e" + m.getName() + teamLabel);
            meta.setLore(Arrays.asList(
                    "§7Left-click: §9Team 1",
                    "§7Right-click: §cTeam 2",
                    "§7Shift-click: §7Unassign"));
            meta.getPersistentDataContainer().set(partyMemberKey, PersistentDataType.STRING, memberId.toString());
            head.setItemMeta(meta);
            inv.setItem(slot, head);

            slot++;
            if (slot % 9 == 8) slot += 2;
            if (slot >= 44) break;
        }

        ItemStack start = new ItemStack(Material.EMERALD);
        ItemMeta startMeta = start.getItemMeta();
        startMeta.setDisplayName("§aStart Team vs Team Duel");
        startMeta.setLore(Arrays.asList("§7Pairs team1[i] vs team2[i]."));
        startMeta.getPersistentDataContainer().set(partyActionKey, PersistentDataType.STRING, "TEAMS_START");
        start.setItemMeta(startMeta);
        inv.setItem(48, start);

        ItemStack reset = new ItemStack(Material.RED_DYE);
        ItemMeta resetMeta = reset.getItemMeta();
        resetMeta.setDisplayName("§cReset Teams");
        resetMeta.getPersistentDataContainer().set(partyActionKey, PersistentDataType.STRING, "TEAMS_RESET");
        reset.setItemMeta(resetMeta);
        inv.setItem(50, reset);

        inv.setItem(53, createItem(Material.BARRIER, "§cClose", null));

        leader.openInventory(inv);
        openGUIs.put(leader.getUniqueId(), new GUI(PARTY_TEAMS_TITLE, System.currentTimeMillis()));
    }

    /** Kit-Auswahl für Party-Duelle. pendingAction: DUEL_ONE (mit pendingTarget), FFA oder TEAMS_START. */
    public void openPartyKitSelect(Player leader, String pendingAction, String pendingTargetUuid) {
        Inventory inv = Bukkit.createInventory(null, 54, PARTY_KIT_SELECT_TITLE);

        Set<String> kits = plugin.getKitManager().getKitNames();
        if (kits.isEmpty()) {
            leader.sendMessage(plugin.getPrefix() + "§cNo kits available!");
            return;
        }

        // Reihenfolge aus kits.yml beibehalten — KEIN alphabetischer Sort.
        List<String> sortedKits = new ArrayList<>(kits);

        int slot = 10;
        for (String kitId : sortedKits) {
            Material previewMat = plugin.getKitManager().getKitPreviewMaterial(kitId);
            ItemStack kitItem = new ItemStack(previewMat);
            ItemMeta meta = kitItem.getItemMeta();
            KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
            meta.setDisplayName(kit != null ? kit.getDisplayName() : kitId);
            meta.setLore(Arrays.asList("§7Click to start with this kit."));
            meta.getPersistentDataContainer().set(partyKitKey, PersistentDataType.STRING, kitId);
            meta.getPersistentDataContainer().set(partyPendingActionKey, PersistentDataType.STRING, pendingAction);
            if (pendingTargetUuid != null) {
                meta.getPersistentDataContainer().set(partyMemberKey, PersistentDataType.STRING, pendingTargetUuid);
            }
            kitItem.setItemMeta(meta);
            inv.setItem(slot, kitItem);
            slot++;
            if (slot % 9 == 8) slot += 2;
            if (slot >= 44) break;
        }

        inv.setItem(49, createItem(Material.BARRIER, "§cClose", null));

        leader.openInventory(inv);
        openGUIs.put(leader.getUniqueId(), new GUI(PARTY_KIT_SELECT_TITLE, System.currentTimeMillis()));
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private int[] centeredColsWithMiddleGap(int n) {
        int center = 4;
        int[] cols = new int[n];
        if (n <= 0) return cols;

        if (n % 2 == 1) {
            int start = center - (n / 2);
            for (int i = 0; i < n; i++) cols[i] = start + i;
        } else {
            int half = n / 2;
            int idx = 0;
            for (int i = half; i >= 1; i--) cols[idx++] = center - i;
            for (int i = 1; i <= half; i++) cols[idx++] = center + i;
        }
        return cols;
    }

    private int getCenteredStartRow(int totalItems, int perRow, int usableRows) {
        int rowsNeeded = (int) Math.ceil(totalItems / (double) perRow);
        return Math.max(0, (usableRows - rowsNeeded) / 2);
    }

    private static class GUI {
        private final String title;
        private final long openTime;

        public GUI(String title, long openTime) {
            this.title = title;
            this.openTime = openTime;
        }

        public String getTitle() { return title; }
        public long getOpenTime() { return openTime; }
    }
}
