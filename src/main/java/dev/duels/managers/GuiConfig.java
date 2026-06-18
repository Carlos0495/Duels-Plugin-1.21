package dev.duels.managers;

import dev.duels.DuelsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lädt {@code guis.yml} und stellt für die GUI-Item-Templates eine
 * config-getriebene Überschreibung bereit. Wenn ein Eintrag in guis.yml
 * fehlt, fällt der Lookup auf hartcodierte Defaults zurück.
 *
 * <p>Aufbau pro Eintrag:</p>
 * <pre>
 * &lt;gui-key&gt;:
 *   &lt;item-key&gt;:
 *     material: BARRIER
 *     name: "&amp;cClose"
 *     slot: 49
 *     lore:
 *       - "&amp;7Close the menu"
 * </pre>
 *
 * <p>Der {@code slot}-Eintrag ist optional — wenn er fehlt, behält der
 * GUI-Code seinen Default-Slot.</p>
 */
public class GuiConfig {

    private final DuelsPlugin plugin;
    private File file;
    private FileConfiguration config;
    private final NamespacedKey leftClickKey;
    private final NamespacedKey rightClickKey;
    private final NamespacedKey itemIdKey;

    public GuiConfig(DuelsPlugin plugin) {
        this.plugin = plugin;
        this.leftClickKey = new NamespacedKey(plugin, "gui_left_click");
        this.rightClickKey = new NamespacedKey(plugin, "gui_right_click");
        this.itemIdKey = new NamespacedKey(plugin, "gui_item_id");
    }

    public NamespacedKey getLeftClickKey() { return leftClickKey; }
    public NamespacedKey getRightClickKey() { return rightClickKey; }
    public NamespacedKey getItemIdKey() { return itemIdKey; }

    public void load() {
        file = new File(plugin.getDataFolder(), "guis.yml");
        if (!file.exists()) {
            try {
                if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create guis.yml: " + e.getMessage());
            }
        }
        config = YamlConfiguration.loadConfiguration(file);
        writeDefaultsIfMissing();
    }

    private void writeDefaultsIfMissing() {
        boolean dirty = false;

        // queue-gui
        dirty |= ensureDefault("queue-gui.info",
                Material.PAPER, "&6Queue: Select a Kit",
                Arrays.asList("&7Pick a kit and you will be queued.",
                        "&7Click again to leave.",
                        "&7Live updates: queue + playing."),
                4);
        dirty |= ensureDefault("queue-gui.close",
                Material.BARRIER, "&cClose", null, 49);
        dirty |= ensureDefault("queue-gui.no-kits",
                Material.BARRIER, "&cNo Kits Available",
                Arrays.asList("&7There are no kits available yet.",
                        "&7Ask an admin to create some kits!"),
                22);

        // kits-gui (preview)
        dirty |= ensureDefault("kits-gui.info",
                Material.PAPER, "&6Select a Kit",
                Arrays.asList("&7Click on a kit to preview it."), 4);
        dirty |= ensureDefault("kits-gui.close",
                Material.BARRIER, "&cClose", null, 49);
        dirty |= ensureDefault("kits-gui.no-kits",
                Material.BARRIER, "&cNo Kits Available",
                Arrays.asList("&7There are no kits available yet.",
                        "&7Ask an admin to create some kits!"),
                22);

        // settings-gui
        dirty |= ensureDefault("settings-gui.edit-layouts",
                Material.CHEST, "&aEdit Kit Inventory Layouts",
                Arrays.asList("&7Edit your personal inventory layout",
                        "&7for each kit separately.", "",
                        "&eClick to edit"),
                11);
        dirty |= ensureDefault("settings-gui.auto-fly-name",
                Material.LIME_DYE, "&bAuto Fly", null, 15);
        dirty |= ensureDefault("settings-gui.close",
                Material.BARRIER, "&cClose", null, 26);

        // edit-layouts-gui
        dirty |= ensureDefault("edit-layouts-gui.info",
                Material.PAPER, "&6Select a Kit to Edit",
                Arrays.asList("&7Click on a kit to &eedit &7your",
                        "&apersonal inventory layout &7for it."),
                4);
        dirty |= ensureDefault("edit-layouts-gui.close",
                Material.BARRIER, "&cClose", null, 49);
        dirty |= ensureDefault("edit-layouts-gui.no-kits",
                Material.BARRIER, "&cNo Kits Available",
                Arrays.asList("&7There are no kits available yet.",
                        "&7Ask an admin to create some kits!"),
                22);

        // edit-layout-gui (per-kit editor)
        dirty |= ensureDefault("edit-layout-gui.fixed-slot",
                Material.RED_STAINED_GLASS_PANE, "&cFixed Slot",
                Arrays.asList("&7This slot is fixed by the kit.",
                        "&7You cannot change armor/offhand layout."),
                -1);
        dirty |= ensureDefault("edit-layout-gui.info",
                Material.PAPER, "&6Editing Layout",
                Arrays.asList("&7Move items in slots &a0-35&7.",
                        "&7Armor/offhand and buttons are locked.", "",
                        "&eClick Save when done."),
                45);
        dirty |= ensureDefault("edit-layout-gui.reset",
                Material.RED_DYE, "&cReset to Default",
                Arrays.asList("&7Reset your inventory layout",
                        "&7back to the default arrangement."),
                51);
        dirty |= ensureDefault("edit-layout-gui.save",
                Material.LIME_DYE, "&aSave Layout",
                Arrays.asList("&7Save your current inventory arrangement",
                        "&7as your personal layout for this kit."),
                52);
        dirty |= ensureDefault("edit-layout-gui.close",
                Material.BARRIER, "&cClose",
                Arrays.asList("&7Close without saving"), 53);

        // stats-gui
        dirty |= ensureDefault("stats-gui.sort",
                Material.HOPPER, "&6Sort: &eKills",
                Arrays.asList("&7Click to cycle sort mode."), 18);
        dirty |= ensureDefault("stats-gui.compare",
                Material.PLAYER_HEAD, "&bCompare Stats",
                Arrays.asList("&7Compare your stats with another player.",
                        "&eClick a head in the leaderboard."),
                17);
        dirty |= ensureDefault("stats-gui.close",
                Material.BARRIER, "&cClose", null, 26);

        // bestof-gui
        dirty |= ensureDefault("bestof-gui.info",
                Material.PAPER, "&dSelect Match Length", null, 4);
        dirty |= ensureDefault("bestof-gui.close",
                Material.BARRIER, "&cCancel", null, 22);

        // duel-gui (kit picker for direct duel)
        dirty |= ensureDefault("duel-gui.info",
                Material.PAPER, "&aSelect a Kit", null, 4);
        dirty |= ensureDefault("duel-gui.close",
                Material.BARRIER, "&cCancel", null, 49);

        // party-menu
        dirty |= ensureDefault("party-menu.duel-1v1",
                Material.IRON_SWORD, "&a1v1 in Party", null, 11);
        dirty |= ensureDefault("party-menu.ffa",
                Material.DIAMOND_SWORD, "&6FFA", null, 13);
        dirty |= ensureDefault("party-menu.team-vs-team",
                Material.SHIELD, "&cTeam vs Team", null, 15);
        dirty |= ensureDefault("party-menu.close",
                Material.BARRIER, "&cClose", null, 22);

        if (dirty) save();
    }

    private boolean ensureDefault(String path, Material mat, String name, List<String> lore, int slot) {
        boolean dirty = false;
        String matPath = path + ".material";
        String namePath = path + ".name";
        String lorePath = path + ".lore";
        String slotPath = path + ".slot";
        if (!config.contains(matPath))  { config.set(matPath, mat.name()); dirty = true; }
        if (!config.contains(namePath)) { config.set(namePath, name); dirty = true; }
        if (!config.contains(lorePath) && lore != null) {
            config.set(lorePath, lore);
            dirty = true;
        }
        if (slot >= 0 && !config.contains(slotPath)) { config.set(slotPath, slot); dirty = true; }
        return dirty;
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save guis.yml: " + e.getMessage());
        }
    }

    public FileConfiguration get() {
        return config;
    }

    // ------- Lookup helpers -------

    public Material getMaterial(String path, Material fallback) {
        String raw = config.getString(path + ".material");
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            return Material.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid material for guis." + path + ": " + raw + " (using " + fallback + ")");
            return fallback;
        }
    }

    public String getName(String path, String fallback) {
        String raw = config.getString(path + ".name");
        if (raw == null) raw = fallback;
        if (raw == null) return "";
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public List<String> getLore(String path, List<String> fallback) {
        if (!config.contains(path + ".lore")) {
            if (fallback == null) return null;
            List<String> out = new ArrayList<>(fallback.size());
            for (String l : fallback) out.add(ChatColor.translateAlternateColorCodes('&', l));
            return out;
        }
        List<String> raw = config.getStringList(path + ".lore");
        if (raw == null) return null;
        List<String> out = new ArrayList<>(raw.size());
        for (String l : raw) out.add(ChatColor.translateAlternateColorCodes('&', l));
        return out;
    }

    public int getSlot(String path, int fallback) {
        return config.getInt(path + ".slot", fallback);
    }

    /**
     * Baut einen ItemStack basierend auf dem konfigurierten Material/Name/Lore.
     * Fehlende Felder werden aus den Fallback-Werten gefüllt.
     */
    public ItemStack buildItem(String path, Material defaultMat, String defaultName, List<String> defaultLore) {
        Material mat = getMaterial(path, defaultMat);
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = getName(path, defaultName);
            if (name != null && !name.isEmpty()) meta.setDisplayName(name);
            List<String> lore = getLore(path, defaultLore);
            if (lore != null) meta.setLore(lore);
            // PDC-Tag für Click-Handler: Items werden per ID erkannt,
            // nicht per Display-Name — so kann der User den Namen frei ändern.
            meta.getPersistentDataContainer().set(itemIdKey,
                    PersistentDataType.STRING, path);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Prüft ob ein ItemStack die gegebene GUI-Item-ID hat. */
    public boolean hasItemId(ItemStack item, String id) {
        if (item == null || !item.hasItemMeta()) return false;
        String val = item.getItemMeta().getPersistentDataContainer()
                .get(itemIdKey, PersistentDataType.STRING);
        return id.equals(val);
    }

    /**
     * Lädt custom-items aus {@code <guiSection>.custom-items} und setzt
     * sie ins Inventar. Jedes Item kann {@code left-click} und/oder
     * {@code right-click} als Command-Action haben.
     *
     * <pre>
     * settings-gui:
     *   custom-items:
     *     ffa-layout:
     *       slot: 13
     *       material: CHEST
     *       name: "&aFFA Layout"
     *       lore:
     *         - "&7Klicke um dein FFA Layout zu bearbeiten"
     *       left-click: "COMMAND:/zxm open invlayoutFFA"
     *       right-click: "COMMAND:/zxm open invlayoutFFA"
     * </pre>
     */
    public void applyCustomItems(org.bukkit.inventory.Inventory inv, String guiSection) {
        ConfigurationSection sec = config.getConfigurationSection(guiSection + ".custom-items");
        if (sec == null) return;

        for (String key : sec.getKeys(false)) {
            ConfigurationSection item = sec.getConfigurationSection(key);
            if (item == null) continue;

            int slot = item.getInt("slot", -1);
            if (slot < 0 || slot >= inv.getSize()) continue;

            Material mat;
            try {
                mat = Material.valueOf(item.getString("material", "PAPER").toUpperCase());
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid material for " + guiSection
                        + ".custom-items." + key + ": " + item.getString("material"));
                mat = Material.PAPER;
            }

            ItemStack stack = new ItemStack(mat);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                String name = item.getString("name", "");
                if (!name.isEmpty()) {
                    meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
                }
                List<String> loreRaw = item.getStringList("lore");
                if (!loreRaw.isEmpty()) {
                    List<String> colored = new ArrayList<>(loreRaw.size());
                    for (String l : loreRaw) colored.add(ChatColor.translateAlternateColorCodes('&', l));
                    meta.setLore(colored);
                }

                String leftClick = item.getString("left-click", "");
                String rightClick = item.getString("right-click", "");
                if (!leftClick.isEmpty()) {
                    meta.getPersistentDataContainer().set(leftClickKey,
                            PersistentDataType.STRING, leftClick);
                }
                if (!rightClick.isEmpty()) {
                    meta.getPersistentDataContainer().set(rightClickKey,
                            PersistentDataType.STRING, rightClick);
                }

                stack.setItemMeta(meta);
            }
            inv.setItem(slot, stack);
        }
    }
}
