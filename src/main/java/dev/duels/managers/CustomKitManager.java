package dev.duels.managers;

import dev.duels.DuelsPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Manages player-created custom kits. Storage: customkits.yml.
 * Custom kits are party-only (1v1, FFA, team) and never appear in queue.
 */
public class CustomKitManager {

    private final DuelsPlugin plugin;
    private File file;
    private FileConfiguration config;

    /** owner UUID → list of custom kits (ordered by index). */
    private final Map<UUID, List<CustomKit>> playerKits = new HashMap<>();

    /** Global blacklist of materials players cannot use in custom kits. */
    private final Set<Material> blacklistedMaterials = EnumSet.noneOf(Material.class);

    public CustomKitManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    // ======================= Loading / Saving =======================

    public void load() {
        file = new File(plugin.getDataFolder(), "customkits.yml");
        if (!file.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create customkits.yml: " + e.getMessage());
            }
        }
        config = YamlConfiguration.loadConfiguration(file);
        loadBlacklist();
        loadAllKits();
    }

    private void loadBlacklist() {
        blacklistedMaterials.clear();
        // Defaults
        String[] defaults = {
            "BARRIER", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
            "COMMAND_BLOCK_MINECART", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW",
            "BEDROCK", "LIGHT", "DEBUG_STICK", "KNOWLEDGE_BOOK", "PETRIFIED_OAK_SLAB",
            "SPAWNER", "BUDDING_AMETHYST", "REINFORCED_DEEPSLATE", "TRIAL_SPAWNER",
            "VAULT"
        };
        List<String> cfgList = plugin.getConfigManager().getMainConfig()
                .getStringList("custom-kits.blacklist");
        if (cfgList.isEmpty()) {
            for (String s : defaults) {
                try { blacklistedMaterials.add(Material.valueOf(s)); } catch (IllegalArgumentException ignored) {}
            }
        } else {
            for (String s : cfgList) {
                try { blacklistedMaterials.add(Material.valueOf(s.trim().toUpperCase())); } catch (IllegalArgumentException ignored) {}
            }
        }
        // Always block spawn eggs
        for (Material m : Material.values()) {
            if (m.name().endsWith("_SPAWN_EGG")) blacklistedMaterials.add(m);
        }
    }

    private void loadAllKits() {
        playerKits.clear();
        // Unregister old custom kits from KitManager
        plugin.getKitManager().clearCustomKits();

        for (String uuidStr : config.getKeys(false)) {
            UUID owner;
            try { owner = UUID.fromString(uuidStr); } catch (IllegalArgumentException e) { continue; }

            ConfigurationSection playerSec = config.getConfigurationSection(uuidStr);
            if (playerSec == null) continue;

            List<CustomKit> kits = new ArrayList<>();
            for (String indexStr : playerSec.getKeys(false)) {
                ConfigurationSection kitSec = playerSec.getConfigurationSection(indexStr);
                if (kitSec == null) continue;

                int index;
                try { index = Integer.parseInt(indexStr); } catch (NumberFormatException e) { continue; }

                CustomKit ck = new CustomKit(owner, index);
                ck.displayName = kitSec.getString("display", "Custom Kit #" + index);
                String iconStr = kitSec.getString("icon", "DIAMOND_SWORD");
                try { ck.icon = Material.valueOf(iconStr); } catch (IllegalArgumentException e) { ck.icon = Material.DIAMOND_SWORD; }

                ConfigurationSection itemsSec = kitSec.getConfigurationSection("items");
                if (itemsSec != null) {
                    for (String slotStr : itemsSec.getKeys(false)) {
                        try {
                            int slot = Integer.parseInt(slotStr);
                            ItemStack item = itemsSec.getItemStack(slotStr);
                            if (item != null && item.getType() != Material.AIR) {
                                ck.items.put(slot, item);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                kits.add(ck);
            }

            kits.sort(Comparator.comparingInt(k -> k.index));
            playerKits.put(owner, kits);

            // Register into KitManager
            for (CustomKit ck : kits) {
                registerIntoKitManager(ck);
            }
        }
        plugin.getLogger().info("Loaded " + playerKits.values().stream().mapToInt(List::size).sum() + " custom kits for " + playerKits.size() + " players.");
    }

    public void save() {
        if (config == null || file == null) return;
        // Clear and rewrite
        for (String key : config.getKeys(false)) {
            config.set(key, null);
        }
        for (Map.Entry<UUID, List<CustomKit>> entry : playerKits.entrySet()) {
            String uuidPath = entry.getKey().toString();
            for (CustomKit ck : entry.getValue()) {
                String path = uuidPath + "." + ck.index;
                config.set(path + ".display", ck.displayName);
                config.set(path + ".icon", ck.icon.name());
                config.set(path + ".items", null);
                for (Map.Entry<Integer, ItemStack> item : ck.items.entrySet()) {
                    config.set(path + ".items." + item.getKey(), item.getValue());
                }
            }
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save customkits.yml: " + e.getMessage());
        }
    }

    private void saveKit(CustomKit ck) {
        if (config == null || file == null) return;
        String path = ck.owner.toString() + "." + ck.index;
        config.set(path + ".display", ck.displayName);
        config.set(path + ".icon", ck.icon.name());
        config.set(path + ".items", null);
        for (Map.Entry<Integer, ItemStack> item : ck.items.entrySet()) {
            config.set(path + ".items." + item.getKey(), item.getValue());
        }
        try { config.save(file); } catch (IOException e) {
            plugin.getLogger().severe("Could not save customkits.yml: " + e.getMessage());
        }
    }

    private void deleteKitFromConfig(CustomKit ck) {
        if (config == null || file == null) return;
        config.set(ck.owner.toString() + "." + ck.index, null);
        // Clean empty player section
        ConfigurationSection sec = config.getConfigurationSection(ck.owner.toString());
        if (sec != null && sec.getKeys(false).isEmpty()) {
            config.set(ck.owner.toString(), null);
        }
        try { config.save(file); } catch (IOException e) {
            plugin.getLogger().severe("Could not save customkits.yml: " + e.getMessage());
        }
    }

    // ======================= CRUD =======================

    public List<CustomKit> getKits(UUID owner) {
        return playerKits.getOrDefault(owner, Collections.emptyList());
    }

    public CustomKit getKit(UUID owner, int index) {
        for (CustomKit ck : getKits(owner)) {
            if (ck.index == index) return ck;
        }
        return null;
    }

    public int getKitCount(UUID owner) {
        return getKits(owner).size();
    }

    /**
     * Creates a new custom kit. Returns the kit, or null if limit reached.
     */
    public CustomKit createKit(Player player, String displayName) {
        UUID owner = player.getUniqueId();
        int limit = getKitLimit(player);
        if (limit <= 0) return null;
        List<CustomKit> kits = playerKits.computeIfAbsent(owner, k -> new ArrayList<>());
        if (kits.size() >= limit) return null;

        int nextIndex = kits.isEmpty() ? 0 : kits.stream().mapToInt(k -> k.index).max().orElse(-1) + 1;
        CustomKit ck = new CustomKit(owner, nextIndex);
        ck.displayName = displayName;
        ck.icon = Material.DIAMOND_SWORD;
        kits.add(ck);

        registerIntoKitManager(ck);
        saveKit(ck);
        return ck;
    }

    public enum GiveResult { SUCCESS, NO_SUCH_KIT }

    /**
     * Copies the owner's custom kit at the given 1-based slot (slot 1 = first
     * kit) to the target player as a NEW custom kit. The owner keeps the
     * original. Admin command — does not enforce the target's tier limit.
     */
    public GiveResult giveKit(UUID ownerId, int slot1Based, UUID targetId) {
        List<CustomKit> ownerKits = getKits(ownerId);
        if (slot1Based < 1 || slot1Based > ownerKits.size()) return GiveResult.NO_SUCH_KIT;
        CustomKit src = ownerKits.get(slot1Based - 1);

        List<CustomKit> targetKits = playerKits.computeIfAbsent(targetId, k -> new ArrayList<>());
        int nextIndex = targetKits.isEmpty() ? 0
                : targetKits.stream().mapToInt(k -> k.index).max().orElse(-1) + 1;
        CustomKit copy = new CustomKit(targetId, nextIndex);
        copy.displayName = src.displayName;
        copy.icon = src.icon;
        for (Map.Entry<Integer, ItemStack> e : src.items.entrySet()) {
            copy.items.put(e.getKey(), e.getValue().clone());
        }
        targetKits.add(copy);
        registerIntoKitManager(copy);
        saveKit(copy);
        return GiveResult.SUCCESS;
    }

    public void updateKit(CustomKit ck) {
        // Re-register to update Kit object in KitManager
        plugin.getKitManager().unregisterCustomKit(ck.getInternalId());
        registerIntoKitManager(ck);
        saveKit(ck);
    }

    public void deleteKit(UUID owner, int index) {
        List<CustomKit> kits = playerKits.get(owner);
        if (kits == null) return;
        CustomKit found = null;
        for (CustomKit ck : kits) {
            if (ck.index == index) { found = ck; break; }
        }
        if (found == null) return;
        kits.remove(found);
        if (kits.isEmpty()) playerKits.remove(owner);
        plugin.getKitManager().unregisterCustomKit(found.getInternalId());
        deleteKitFromConfig(found);
    }

    // ======================= Permissions =======================

    /**
     * Returns the max number of custom kits the player can have.
     * Checks tiers from config; returns highest matching limit.
     */
    public int getKitLimit(Player player) {
        if (!plugin.getConfigManager().getMainConfig().getBoolean("custom-kits.enabled", true)) return 0;

        ConfigurationSection tiers = plugin.getConfigManager().getMainConfig()
                .getConfigurationSection("custom-kits.tiers");
        if (tiers == null) return 0;

        int max = 0;
        for (String key : tiers.getKeys(false)) {
            String perm = tiers.getString(key + ".permission");
            int limit = tiers.getInt(key + ".limit", 0);
            if (perm != null && player.hasPermission(perm) && limit > max) {
                max = limit;
            }
        }
        return max;
    }

    /**
     * Checks custom kits for a player and deletes excess kits if
     * their permission no longer allows them. Called on join + reload.
     */
    public void enforcePermissionLimits(Player player) {
        UUID owner = player.getUniqueId();
        int limit = getKitLimit(player);
        List<CustomKit> kits = playerKits.get(owner);
        if (kits == null || kits.isEmpty()) return;

        if (limit <= 0) {
            // No permission at all — delete ALL
            for (CustomKit ck : new ArrayList<>(kits)) {
                plugin.getKitManager().unregisterCustomKit(ck.getInternalId());
                deleteKitFromConfig(ck);
            }
            playerKits.remove(owner);
            player.sendMessage(plugin.getConfigManager().prefixed(
                    "custom-kit.permission-lost-all",
                    "&cYour custom kits have been deleted because you no longer have permission."));
            return;
        }

        // Delete excess kits (keep the first N)
        while (kits.size() > limit) {
            CustomKit last = kits.remove(kits.size() - 1);
            plugin.getKitManager().unregisterCustomKit(last.getInternalId());
            deleteKitFromConfig(last);
        }
        if (kits.isEmpty()) playerKits.remove(owner);
    }

    // ======================= KitManager Integration =======================

    private void registerIntoKitManager(CustomKit ck) {
        KitManager.Kit kit = new KitManager.Kit(ck.getInternalId());
        kit.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', ck.displayName));
        kit.setPreviewMaterial(ck.icon);

        for (Map.Entry<Integer, ItemStack> entry : ck.items.entrySet()) {
            kit.setItem(entry.getKey(), entry.getValue().clone());
            // Jeder Block, der im Custom-Kit liegt, darf platziert werden.
            // Das Abbauen regelt der BlockBreakListener separat (nur selbst
            // platzierte Blöcke + die Arena-Breakable-Liste), damit man NICHT
            // beliebige Arena-Blöcke gleichen Materials zerstören kann.
            ItemStack it = entry.getValue();
            if (it != null && it.getType().isBlock()) {
                kit.getPlaceableBlocks().add(it.getType());
            }
        }

        plugin.getKitManager().registerCustomKit(ck.getInternalId(), kit);
    }

    // ======================= Item Picker Helpers =======================

    public boolean isBlacklisted(Material material) {
        return blacklistedMaterials.contains(material);
    }

    /**
     * Returns all materials available for the item picker (sorted by name).
     */
    public List<Material> getAvailableItems() {
        List<Material> result = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isItem()) continue;
            if (m == Material.AIR || m.name().contains("AIR") && m != Material.AIR) {
                // Skip all air types
                if (m.name().endsWith("_AIR") || m == Material.AIR) continue;
            }
            if (isBlacklisted(m)) continue;
            if (m.name().startsWith("LEGACY_")) continue;
            result.add(m);
        }
        result.sort(Comparator.comparing(Material::name));
        return result;
    }

    // ======================= Item Categories =======================

    /** A picker category (tab) like Creative mode. */
    public static class ItemCategory {
        public final String id;
        public final String display;
        public final Material icon;
        public ItemCategory(String id, String display, Material icon) {
            this.id = id; this.display = display; this.icon = icon;
        }
    }

    private static final List<ItemCategory> CATEGORIES = Arrays.asList(
            new ItemCategory("all",      "&fAll",            Material.CHEST),
            new ItemCategory("pvp",      "&cPvP",            Material.DIAMOND_SWORD),
            new ItemCategory("tools",    "&6Tools",          Material.DIAMOND_PICKAXE),
            new ItemCategory("armor",    "&bArmor",          Material.DIAMOND_CHESTPLATE),
            new ItemCategory("blocks",   "&aBlocks",         Material.GRASS_BLOCK),
            new ItemCategory("food",     "&eFood",           Material.COOKED_BEEF),
            new ItemCategory("brewing",  "&dPotions",        Material.BREWING_STAND),
            new ItemCategory("redstone", "&4Redstone",       Material.REDSTONE),
            new ItemCategory("misc",     "&7Misc",           Material.ENDER_PEARL)
    );

    public List<ItemCategory> getItemCategories() { return CATEGORIES; }

    public ItemCategory getCategory(String id) {
        if (id == null) return CATEGORIES.get(0);
        for (ItemCategory c : CATEGORIES) if (c.id.equalsIgnoreCase(id)) return c;
        return CATEGORIES.get(0);
    }

    /** Items in a category (already blacklist-filtered, sorted by name). */
    public List<Material> getCategoryItems(String categoryId) {
        List<Material> all = getAvailableItems();
        if (categoryId == null || categoryId.equalsIgnoreCase("all")) return all;
        List<Material> result = new ArrayList<>();
        for (Material m : all) {
            if (categoryOf(m).equals(categoryId.toLowerCase(Locale.ROOT))) result.add(m);
        }
        return result;
    }

    /** Classify a material into exactly one picker category id. */
    public static String categoryOf(Material m) {
        String n = m.name();
        // PvP / combat
        if (n.endsWith("_SWORD") || n.endsWith("_AXE")
                || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT")
                || n.equals("MACE") || n.equals("SHIELD")
                || n.equals("ARROW") || n.equals("SPECTRAL_ARROW") || n.equals("TIPPED_ARROW")
                || n.equals("TOTEM_OF_UNDYING") || n.equals("FIRE_CHARGE")
                || n.equals("END_CRYSTAL")) return "pvp";
        // Armor
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                || n.endsWith("_BOOTS") || n.equals("ELYTRA") || n.endsWith("_HORSE_ARMOR")
                || n.equals("TURTLE_HELMET")) return "armor";
        // Tools / utility
        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || n.equals("SHEARS") || n.equals("FLINT_AND_STEEL") || n.equals("FISHING_ROD")
                || n.equals("COMPASS") || n.equals("RECOVERY_COMPASS") || n.equals("CLOCK")
                || n.equals("SPYGLASS") || n.equals("BRUSH") || n.equals("LEAD")
                || n.equals("NAME_TAG") || n.equals("BUCKET") || n.equals("WATER_BUCKET")
                || n.equals("LAVA_BUCKET") || n.equals("POWDER_SNOW_BUCKET")
                || n.equals("ENDER_EYE") || n.equals("FIREWORK_ROCKET")) return "tools";
        // Potions & brewing
        if (n.equals("POTION") || n.equals("SPLASH_POTION") || n.equals("LINGERING_POTION")
                || n.equals("EXPERIENCE_BOTTLE") || n.equals("GLASS_BOTTLE")
                || n.equals("DRAGON_BREATH") || n.equals("BLAZE_POWDER") || n.equals("NETHER_WART")
                || n.equals("FERMENTED_SPIDER_EYE") || n.equals("BREWING_STAND")
                || n.equals("GHAST_TEAR") || n.equals("MAGMA_CREAM")) return "brewing";
        // Redstone (check before generic blocks)
        if (n.equals("REDSTONE") || n.equals("REDSTONE_BLOCK") || n.equals("REDSTONE_TORCH")
                || n.equals("REPEATER") || n.equals("COMPARATOR") || n.equals("OBSERVER")
                || n.equals("PISTON") || n.equals("STICKY_PISTON") || n.equals("DISPENSER")
                || n.equals("DROPPER") || n.equals("HOPPER") || n.equals("LEVER")
                || n.equals("TNT") || n.equals("REDSTONE_LAMP") || n.equals("TARGET")
                || n.equals("DAYLIGHT_DETECTOR") || n.equals("SLIME_BLOCK") || n.equals("HONEY_BLOCK")
                || n.equals("TRIPWIRE_HOOK") || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE")) return "redstone";
        // Food
        if (m.isEdible()) return "food";
        // Generic placeable blocks
        if (m.isBlock()) return "blocks";
        return "misc";
    }

    /**
     * Checks if a material can be enchanted in the custom kit enchant menu.
     * Only armor and tools (swords, axes, pickaxes, shovels, hoes, bows, crossbows, trident, fishing rod, mace).
     */
    public static boolean isEnchantable(Material material) {
        if (material == null) return false;
        String n = material.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE")
                || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT")
                || n.equals("FISHING_ROD") || n.equals("SHEARS") || n.equals("FLINT_AND_STEEL")
                || n.equals("MACE") || n.equals("SHIELD") || n.equals("ELYTRA");
    }

    // ======================= World Check =======================

    public boolean isWorldAllowed(Player player) {
        return plugin.getConfigManager().isWorldAllowed(player, "custom-kit");
    }

    // ======================= Data Class =======================

    public static class CustomKit {
        final UUID owner;
        final int index;
        String displayName;
        Material icon;
        final Map<Integer, ItemStack> items = new HashMap<>();

        public CustomKit(UUID owner, int index) {
            this.owner = owner;
            this.index = index;
            this.displayName = "Custom Kit #" + index;
            this.icon = Material.DIAMOND_SWORD;
        }

        public UUID getOwner() { return owner; }
        public int getIndex() { return index; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String name) { this.displayName = name; }
        public Material getIcon() { return icon; }
        public void setIcon(Material icon) { this.icon = icon; }
        public Map<Integer, ItemStack> getItems() { return items; }

        /** Internal kit id used for registration in KitManager. */
        public String getInternalId() {
            return "ckit_" + owner.toString().replace("-", "").substring(0, 8) + "_" + index;
        }
    }
}
