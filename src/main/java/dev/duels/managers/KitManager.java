package dev.duels.managers;

import dev.duels.DuelsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.*;

public class KitManager {

    private final DuelsPlugin plugin;
    // LinkedHashMap behält die Insertion-Order — KitNames werden in der
    // Reihenfolge geliefert, in der sie in kits.yml stehen.
    private final Map<String, Kit> kits = new LinkedHashMap<>(); // key = kitId (clean)
    private final Map<UUID, Map<String, ItemStack[]>> playerKitLayouts = new HashMap<>();

    /** Tracks ids of custom (player-created) kits registered into the kits map.
     *  These are excluded from getKitNames() so they don't appear in queue/edit-layouts/duel-gui. */
    private final java.util.Set<String> customKitIds = new java.util.HashSet<>();

    /** PDC key marking items from custom kits as non-droppable (safety: custom
     *  kit items must never leak into the economy). */
    private final org.bukkit.NamespacedKey noDropKey;

    public KitManager(DuelsPlugin plugin) {
        this.plugin = plugin;
        this.noDropKey = new org.bukkit.NamespacedKey(plugin, "customkit_nodrop");
    }

    public org.bukkit.NamespacedKey getNoDropKey() { return noDropKey; }

    /** Returns true if the item is a non-droppable custom-kit item. */
    public boolean isNoDrop(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(noDropKey, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** Clones the item (if non-null) and tags it no-drop when requested. */
    private ItemStack applyNoDrop(ItemStack item, boolean noDrop) {
        if (item == null) return null;
        ItemStack it = item.clone();
        if (noDrop) tagNoDrop(it);
        return it;
    }

    /** Tags a cloned item as non-droppable and returns it. */
    private ItemStack tagNoDrop(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return item;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(noDropKey,
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void loadKits() {
        // Preserve custom kits across reloads
        Map<String, Kit> savedCustom = new LinkedHashMap<>();
        for (String id : customKitIds) {
            Kit k = kits.get(id);
            if (k != null) savedCustom.put(id, k);
        }
        kits.clear();
        kits.putAll(savedCustom);

        if (!plugin.getConfigManager().getKitsConfig().contains("kits")) {
            plugin.getLogger().info("Loaded 0 kits");
            return;
        }

        ConfigurationSection kitsSection = plugin.getConfigManager().getKitsConfig().getConfigurationSection("kits");
        if (kitsSection == null) {
            plugin.getLogger().info("Loaded 0 kits");
            return;
        }

        for (String kitId : kitsSection.getKeys(false)) {
            Kit kit = new Kit(kitId);

            // Display name (colored). Fallback for older configs: ".name"
            String storedDisplay = kitsSection.getString(kitId + ".displayName", null);
            if (storedDisplay == null) {
                storedDisplay = kitsSection.getString(kitId + ".name", kitId);
            }
            kit.setDisplayName(colorize(storedDisplay));

            // Preview Material
            String previewMatStr = kitsSection.getString(kitId + ".preview_material", "DIAMOND_SWORD");
            try {
                kit.setPreviewMaterial(Material.valueOf(previewMatStr));
            } catch (IllegalArgumentException e) {
                kit.setPreviewMaterial(Material.DIAMOND_SWORD);
            }

            // Breakable Blocks laden (Liste von Material-Namen, case-insensitive,
            // ungültige Einträge werden geloggt und ignoriert).
            kit.getBreakableBlocks().clear();
            java.util.List<String> breakable =
                    kitsSection.getStringList(kitId + ".breakable-blocks");
            for (String entry : breakable) {
                if (entry == null || entry.trim().isEmpty()) continue;
                try {
                    Material m = Material.matchMaterial(entry.trim().toUpperCase());
                    if (m == null) {
                        plugin.getLogger().warning("Kit '" + kitId + "': unknown material in breakable-blocks: " + entry);
                        continue;
                    }
                    kit.getBreakableBlocks().add(m);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Kit '" + kitId + "': bad breakable-blocks entry: " + entry);
                }
            }

            // Placeable Blocks laden (gleiches Format wie breakable-blocks).
            // Crystal-Kit will typischerweise OBSIDIAN + END_CRYSTAL platzieren
            // dürfen; Buildup-Modi z.B. zusätzlich COBBLESTONE etc.
            kit.getPlaceableBlocks().clear();
            java.util.List<String> placeable =
                    kitsSection.getStringList(kitId + ".placeable-blocks");
            for (String entry : placeable) {
                if (entry == null || entry.trim().isEmpty()) continue;
                try {
                    Material m = Material.matchMaterial(entry.trim().toUpperCase());
                    if (m == null) {
                        plugin.getLogger().warning("Kit '" + kitId + "': unknown material in placeable-blocks: " + entry);
                        continue;
                    }
                    kit.getPlaceableBlocks().add(m);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Kit '" + kitId + "': bad placeable-blocks entry: " + entry);
                }
            }

            // Pro-Kit Duell-Dauer:
            //   duration-seconds: 0  → globaler Default (config.yml: duel-time)
            //   duration-seconds: N  → exakt N Sekunden für dieses Kit
            //   until-death: true    → kein Timer, läuft bis einer stirbt
            // Beim ersten Laden werden Default-Werte (0 / false) automatisch
            // in kits.yml geschrieben, damit der Admin sie einfach finden
            // und editieren kann (User-Wunsch: "ich weiß nicht wie ich die
            // kit timer einstellen kann").
            boolean wroteDefaults = false;
            if (!kitsSection.contains(kitId + ".duration-seconds")) {
                kitsSection.set(kitId + ".duration-seconds", 0);
                wroteDefaults = true;
            }
            if (!kitsSection.contains(kitId + ".until-death")) {
                kitsSection.set(kitId + ".until-death", false);
                wroteDefaults = true;
            }
            if (wroteDefaults) {
                plugin.getConfigManager().saveKitsConfig();
            }
            kit.setDurationSeconds(kitsSection.getInt(kitId + ".duration-seconds", 0));
            kit.setUntilDeath(kitsSection.getBoolean(kitId + ".until-death", false));

            // Pro-Kit Saturation beim Duel-Start, angegeben in "Keulen"
            //   saturation: -1  → Default (volle Saturation, 20)
            //   saturation: 3   → 3 Keulen (= 6 Saturation-Punkte), 0.5er
            //                      Schritte erlaubt (0.5 Keule = 1 Punkt).
            // Beim ersten Laden wird der Default (-1) in kits.yml geschrieben.
            boolean wroteExtra = false;
            if (!kitsSection.contains(kitId + ".saturation")) {
                kitsSection.set(kitId + ".saturation", -1);
                wroteExtra = true;
            }
            // Pro-Kit Auto-Potions beim Duel-Start. Format pro Eintrag:
            //   "TYPE:LEVEL:SEKUNDEN"  (LEVEL 1-basiert; SEKUNDEN optional,
            //   weglassen = ganzes Match). Beispiel: "SPEED:2:30", "REGENERATION:1".
            if (!kitsSection.contains(kitId + ".potions")) {
                kitsSection.set(kitId + ".potions", new java.util.ArrayList<String>());
                wroteExtra = true;
            }
            // Fester GUI-Slot in den Kit-Auswahl-GUIs (nur aktiv wenn
            //   config kits.use-custom-slots: true). -1 = Auto-Anordnung.
            if (!kitsSection.contains(kitId + ".gui-slot")) {
                kitsSection.set(kitId + ".gui-slot", -1);
                wroteExtra = true;
            }
            if (wroteExtra) {
                plugin.getConfigManager().saveKitsConfig();
            }
            kit.setStartSaturation(kitsSection.getDouble(kitId + ".saturation", -1));
            kit.setGuiSlot(kitsSection.getInt(kitId + ".gui-slot", -1));
            kit.getStartEffects().clear();
            for (String entry : kitsSection.getStringList(kitId + ".potions")) {
                org.bukkit.potion.PotionEffect eff = parsePotionEffect(entry);
                if (eff != null) {
                    kit.getStartEffects().add(eff);
                } else if (entry != null && !entry.trim().isEmpty()) {
                    plugin.getLogger().warning("Kit '" + kitId + "': bad potion entry: " + entry);
                }
            }

            // Items laden
            if (kitsSection.contains(kitId + ".items")) {
                ConfigurationSection itemsSection = kitsSection.getConfigurationSection(kitId + ".items");
                if (itemsSection != null) {
                    for (String slotStr : itemsSection.getKeys(false)) {
                        try {
                            int slot = Integer.parseInt(slotStr);
                            ItemStack item = itemsSection.getItemStack(slotStr);
                            if (item != null && item.getType() != Material.AIR) {
                                kit.setItem(slot, item);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            kits.put(kitId, kit);

            // Diagnose-Log: zeigt dem Admin, welche Materialien das Plugin
            // tatsächlich für dieses Kit als placeable / breakable geladen
            // hat (User-Wunsch: "ich glaube placeable blocks geht nicht").
            // Hilft bei YAML-Typos: wenn die Liste leer ist, ist entweder
            // der Key falsch geschrieben oder das Material ungültig.
            StringBuilder pb = new StringBuilder();
            for (Material m : kit.getPlaceableBlocks()) {
                if (pb.length() > 0) pb.append(", ");
                pb.append(m.name());
            }
            StringBuilder bb = new StringBuilder();
            for (Material m : kit.getBreakableBlocks()) {
                if (bb.length() > 0) bb.append(", ");
                bb.append(m.name());
            }
            plugin.getLogger().info("Kit '" + kitId
                    + "' placeable=[" + pb.toString()
                    + "] breakable=[" + bb.toString() + "]");
        }

        plugin.getLogger().info("Loaded " + kits.size() + " kits");
    }

    public void saveKit(String kitId, Kit kit) {
        // Vor dem Schreiben Disk-Stand laden, sonst werden Hand-Edits an
        // anderen Kits in kits.yml beim nächsten saveKit überschrieben
        // (Config-Reset-Fix).
        plugin.getConfigManager().reloadKitsConfigFromDisk();
        String path = "kits." + kitId;

        // keep legacy field if you want (optional), but the important one is displayName
        plugin.getConfigManager().getKitsConfig().set(path + ".name", kitId);

        // store displayName with & codes (clean YAML)
        plugin.getConfigManager().getKitsConfig().set(path + ".displayName", uncolorize(kit.getDisplayName()));

        plugin.getConfigManager().getKitsConfig().set(path + ".preview_material", kit.getPreviewMaterial().name());

        // Breakable blocks als String-Liste speichern (nur wenn nicht leer,
        // sonst wird die Liste explizit geleert für sauberes YAML).
        if (kit.getBreakableBlocks().isEmpty()) {
            // Nicht überschreiben falls User die Liste manuell in YAML editiert
            // hat und das Kit nur über UI gespeichert wird ohne breakable-Blocks
            // verändert zu haben — lese existierenden Wert und respektiere ihn.
            if (!plugin.getConfigManager().getKitsConfig().contains(path + ".breakable-blocks")) {
                plugin.getConfigManager().getKitsConfig().set(path + ".breakable-blocks", new java.util.ArrayList<String>());
            }
        } else {
            java.util.List<String> names = new java.util.ArrayList<>(kit.getBreakableBlocks().size());
            for (Material m : kit.getBreakableBlocks()) names.add(m.name());
            java.util.Collections.sort(names);
            plugin.getConfigManager().getKitsConfig().set(path + ".breakable-blocks", names);
        }

        // Placeable Blocks ebenso speichern.
        if (kit.getPlaceableBlocks().isEmpty()) {
            if (!plugin.getConfigManager().getKitsConfig().contains(path + ".placeable-blocks")) {
                plugin.getConfigManager().getKitsConfig().set(path + ".placeable-blocks", new java.util.ArrayList<String>());
            }
        } else {
            java.util.List<String> names = new java.util.ArrayList<>(kit.getPlaceableBlocks().size());
            for (Material m : kit.getPlaceableBlocks()) names.add(m.name());
            java.util.Collections.sort(names);
            plugin.getConfigManager().getKitsConfig().set(path + ".placeable-blocks", names);
        }

        // Alte Items löschen
        plugin.getConfigManager().getKitsConfig().set(path + ".items", null);

        // Neue Items speichern
        for (Map.Entry<Integer, ItemStack> entry : kit.getItems().entrySet()) {
            plugin.getConfigManager().getKitsConfig().set(path + ".items." + entry.getKey(), entry.getValue());
        }

        plugin.getConfigManager().saveKitsConfig();
        kits.put(kitId, kit);
    }

    public void deleteKit(String kitId) {
        kits.remove(kitId);
        plugin.getConfigManager().reloadKitsConfigFromDisk();
        plugin.getConfigManager().getKitsConfig().set("kits." + kitId, null);
        plugin.getConfigManager().saveKitsConfig();
    }

    public void giveKit(Player player, String kitId) {
        Kit kit = kits.get(kitId);
        if (kit == null) return;

        PlayerInventory inv = player.getInventory();
        inv.clear();

        boolean noDrop = isCustomKit(kitId);

        ItemStack[] customLayout = getCustomLayout(player.getUniqueId(), kitId);

        if (customLayout != null) {
            for (int i = 0; i < Math.min(customLayout.length, 36); i++) {
                if (customLayout[i] != null && customLayout[i].getType() != Material.AIR) {
                    ItemStack it = customLayout[i].clone();
                    if (noDrop) tagNoDrop(it);
                    inv.setItem(i, it);
                }
            }
        } else {
            for (Map.Entry<Integer, ItemStack> entry : kit.getItems().entrySet()) {
                int slot = entry.getKey();
                ItemStack item = entry.getValue();

                if (slot >= 0 && slot < 36) {
                    ItemStack it = item.clone();
                    if (noDrop) tagNoDrop(it);
                    inv.setItem(slot, it);
                }
            }
        }

        inv.setBoots(applyNoDrop(kit.getItem(100), noDrop));
        inv.setLeggings(applyNoDrop(kit.getItem(101), noDrop));
        inv.setChestplate(applyNoDrop(kit.getItem(102), noDrop));
        inv.setHelmet(applyNoDrop(kit.getItem(103), noDrop));

        inv.setItemInOffHand(applyNoDrop(kit.getItem(99), noDrop));

        // Persönliche Armor-Trims des Spielers auf die gerade angezogene
        // Rüstung anwenden (nur wenn duels.armortrim Permission). Wirkt
        // damit automatisch für JEDES Kit, ohne im Kit selbst Trims
        // hinterlegen zu müssen.
        if (plugin.getArmorTrimManager() != null) {
            plugin.getArmorTrimManager().applyTrimsToArmor(player);
        }

        player.updateInventory();
    }

    /**
     * Wendet die Pro-Kit Start-Effekte an: Saturation (in "Keulen", -1 = voll)
     * und Auto-Potions. Wird beim Duel-/FFA-/Team-Start aufgerufen (NICHT beim
     * Kit-Preview, damit Vorschau-Inventare keine Effekte vergeben).
     */
    public void applyKitStartEffects(Player player, String kitId) {
        if (player == null) return;
        Kit kit = kits.get(kitId);
        if (kit == null) return;

        // Saturation: -1 = Default (voll = 20). Sonst Keulen * 2 = Punkte,
        // geclamped auf [0, 20].
        double sat = kit.getStartSaturation();
        if (sat >= 0) {
            float points = (float) (sat * 2.0);
            if (points < 0f) points = 0f;
            if (points > 20f) points = 20f;
            player.setSaturation(points);
        }

        // Auto-Potions anwenden.
        for (org.bukkit.potion.PotionEffect eff : kit.getStartEffects()) {
            if (eff != null) {
                player.addPotionEffect(eff, true);
            }
        }
    }

    public void giveKitPreview(Player player, String kitId) {
        Kit kit = kits.get(kitId);
        if (kit == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("queue.kit-not-found", "&cKit not found: {kit}", java.util.Map.of("kit", kitId)));
            return;
        }
        // Welt-Einschränkung: Kit-Vorschau nur in konfigurierten Welten.
        if (plugin.getConfigManager() != null
                && !plugin.getConfigManager().isWorldAllowed(player, "kit-preview")) {
            player.sendMessage(plugin.getConfigManager().prefixed("preview.wrong-world",
                    "&cYou can only preview kits in the lobby world."));
            return;
        }

        ItemStack[] saved = player.getInventory().getContents();
        ItemStack[] savedArmor = player.getInventory().getArmorContents();
        ItemStack savedOffhand = player.getInventory().getItemInOffHand();

        giveKit(player, kitId);

        player.sendMessage(plugin.getConfigManager().prefixed("preview.previewing", "&aPreviewing kit: {kit}", java.util.Map.of("kit", kit.getDisplayName())));
        player.sendMessage(plugin.getConfigManager().prefixed("preview.return-hint", "&7Use &c/spawn &7to return to your normal inventory."));

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !plugin.getDuelManager().isInDuel(player.getUniqueId())) {
                player.getInventory().setContents(saved);
                player.getInventory().setArmorContents(savedArmor);
                player.getInventory().setItemInOffHand(savedOffhand);
                player.updateInventory();
                player.sendMessage(plugin.getConfigManager().prefixed("preview.restored", "&7Your inventory has been restored."));
            }
        }, 20L * 60L * 5L);
    }

    public void saveCustomLayout(UUID playerId, String kitId, ItemStack[] layout) {
        Map<String, ItemStack[]> layouts = playerKitLayouts.computeIfAbsent(playerId, k -> new HashMap<>());
        layouts.put(kitId, layout);

        String path = playerId.toString() + ".kitLayouts." + kitId;
        plugin.getConfigManager().getPlayersConfig().set(path, null);

        for (int i = 0; i < layout.length; i++) {
            if (layout[i] != null && layout[i].getType() != Material.AIR) {
                plugin.getConfigManager().getPlayersConfig().set(path + "." + i, layout[i]);
            }
        }

        plugin.getConfigManager().savePlayersConfig();
    }

    public ItemStack[] getCustomLayout(UUID playerId, String kitId) {
        Map<String, ItemStack[]> layouts = playerKitLayouts.get(playerId);
        if (layouts == null) return null;
        return layouts.get(kitId);
    }

    public void deleteCustomLayout(UUID playerId, String kitId) {
        Map<String, ItemStack[]> layouts = playerKitLayouts.get(playerId);
        if (layouts != null) {
            layouts.remove(kitId);
            plugin.getConfigManager().getPlayersConfig().set(playerId.toString() + ".kitLayouts." + kitId, null);
            plugin.getConfigManager().savePlayersConfig();
        }
    }

    public void loadPlayerKitLayouts() {
        playerKitLayouts.clear();

        for (String playerIdStr : plugin.getConfigManager().getPlayersConfig().getKeys(false)) {
            if (!isValidUUID(playerIdStr)) continue;

            UUID playerId = UUID.fromString(playerIdStr);
            String path = playerIdStr + ".kitLayouts";

            if (!plugin.getConfigManager().getPlayersConfig().contains(path)) continue;

            ConfigurationSection layoutsSection = plugin.getConfigManager().getPlayersConfig().getConfigurationSection(path);
            if (layoutsSection == null) continue;

            Map<String, ItemStack[]> layouts = new HashMap<>();

            for (String kitId : layoutsSection.getKeys(false)) {
                ItemStack[] layout = new ItemStack[36];
                ConfigurationSection kitSection = layoutsSection.getConfigurationSection(kitId);
                if (kitSection == null) continue;

                for (String slotStr : kitSection.getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(slotStr);
                        if (slot >= 0 && slot < 36) {
                            layout[slot] = kitSection.getItemStack(slotStr);
                        }
                    } catch (NumberFormatException ignored) {}
                }

                layouts.put(kitId, layout);
            }

            playerKitLayouts.put(playerId, layouts);
        }
    }

    public boolean kitExists(String kitId) {
        return kits.containsKey(kitId);
    }

    public Set<String> getKitNames() {
        if (customKitIds.isEmpty()) return kits.keySet();
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (String id : kits.keySet()) {
            if (!customKitIds.contains(id)) result.add(id);
        }
        return result;
    }

    public Kit getKit(String kitId) {
        return kits.get(kitId);
    }

    // ============ Custom Kit Registration ============

    public void registerCustomKit(String id, Kit kit) {
        kits.put(id, kit);
        customKitIds.add(id);
    }

    public void unregisterCustomKit(String id) {
        kits.remove(id);
        customKitIds.remove(id);
    }

    public void clearCustomKits() {
        for (String id : new java.util.ArrayList<>(customKitIds)) {
            kits.remove(id);
        }
        customKitIds.clear();
    }

    public boolean isCustomKit(String kitId) {
        return customKitIds.contains(kitId);
    }

    public java.util.Set<String> getCustomKitIds() {
        return java.util.Collections.unmodifiableSet(customKitIds);
    }

    public Material getKitPreviewMaterial(String kitId) {
        Kit kit = kits.get(kitId);
        return kit != null ? kit.getPreviewMaterial() : Material.DIAMOND_SWORD;
    }

    private boolean isValidUUID(String string) {
        try {
            UUID.fromString(string);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String colorize(String s) {
        if (s == null) return null;
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static String uncolorize(String s) {
        if (s == null) return null;
        // store with '&' codes so YAML stays readable
        return s.replace('§', '&');
    }

    /**
     * Parst einen Auto-Potion-Eintrag im Format "TYPE:LEVEL:SEKUNDEN".
     * LEVEL ist 1-basiert (1 = Stufe I → amplifier 0). SEKUNDEN ist optional;
     * fehlt es (oder <= 0), gilt der Effekt quasi das ganze Match (sehr lange
     * Dauer). Gibt {@code null} zurück, wenn der Eintrag ungültig ist.
     */
    private static org.bukkit.potion.PotionEffect parsePotionEffect(String entry) {
        if (entry == null) return null;
        String s = entry.trim();
        if (s.isEmpty()) return null;
        String[] parts = s.split(":");
        if (parts.length == 0) return null;

        org.bukkit.potion.PotionEffectType type =
                org.bukkit.potion.PotionEffectType.getByName(parts[0].trim().toUpperCase());
        if (type == null) return null;

        int level = 1;
        if (parts.length >= 2) {
            try { level = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {}
        }
        if (level < 1) level = 1;
        int amplifier = level - 1;

        int durationTicks;
        if (parts.length >= 3) {
            int seconds = 0;
            try { seconds = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException ignored) {}
            durationTicks = seconds > 0 ? seconds * 20 : 1000000;
        } else {
            durationTicks = 1000000; // ganzes Match
        }
        // ambient=false, particles=true, icon=true (Standard-Darstellung)
        return new org.bukkit.potion.PotionEffect(type, durationTicks, amplifier, false, true, true);
    }

    public static class Kit {
        private final String id; // clean id / config key
        private String displayName; // colored
        private Material previewMaterial;
        private final Map<Integer, ItemStack> items = new HashMap<>();
        // Konfigurierbare Materialien, die im Duel mit diesem Kit gebrochen
        // bzw. (zwischen)gesetzt werden dürfen. Leer = nichts darf gebrochen
        // werden (Standardverhalten für Pure-PvP-Kits). Crystal-Kits haben
        // typischerweise OBSIDIAN, BEDROCK (place by crystal explosion target)
        // und END_CRYSTAL hier drin.
        private final java.util.Set<Material> breakableBlocks = java.util.EnumSet.noneOf(Material.class);
        // Materialien, die im Duel platziert werden dürfen. Wird typischerweise
        // für Crystal-Kits (END_CRYSTAL, OBSIDIAN), Buildup-Modi etc. genutzt.
        // Leer = nichts darf platziert werden.
        private final java.util.Set<Material> placeableBlocks = java.util.EnumSet.noneOf(Material.class);

        // Pro-Kit Duell-Dauer in Sekunden. -1 oder untilDeath=true → kein
        // Timer (Match läuft bis einer stirbt). 0 oder leer → globaler
        // Default aus config (duel-time).
        private int durationSeconds = 0;
        private boolean untilDeath = false;

        // Pro-Kit Saturation beim Start (in "Keulen"; -1 = Default/voll).
        private double startSaturation = -1;
        // Pro-Kit Auto-Potions beim Start.
        private final java.util.List<org.bukkit.potion.PotionEffect> startEffects = new java.util.ArrayList<>();

        // Fester GUI-Slot (0-53) in den Kit-Auswahl-GUIs. -1 = automatische,
        // zentrierte Anordnung wie bisher. Greift nur wenn der globale Toggle
        // (config: kits.use-custom-slots) auf true steht.
        private int guiSlot = -1;

        public Kit(String id) {
            this.id = id;
            this.previewMaterial = Material.DIAMOND_SWORD;
        }

        public int getDurationSeconds() { return durationSeconds; }
        public void setDurationSeconds(int v) { this.durationSeconds = v; }
        public boolean isUntilDeath() { return untilDeath; }
        public void setUntilDeath(boolean b) { this.untilDeath = b; }

        public double getStartSaturation() { return startSaturation; }
        public void setStartSaturation(double v) { this.startSaturation = v; }
        public java.util.List<org.bukkit.potion.PotionEffect> getStartEffects() { return startEffects; }

        public int getGuiSlot() { return guiSlot; }
        public void setGuiSlot(int slot) { this.guiSlot = slot; }

        public String getId() { return id; }

        public String getDisplayName() {
            return displayName != null ? displayName : id;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public Material getPreviewMaterial() { return previewMaterial; }
        public void setPreviewMaterial(Material material) { this.previewMaterial = material; }
        public Map<Integer, ItemStack> getItems() { return items; }

        public void setItem(int slot, ItemStack item) {
            items.put(slot, item);
        }

        public ItemStack getItem(int slot) {
            return items.get(slot);
        }

        public java.util.Set<Material> getBreakableBlocks() { return breakableBlocks; }
        public java.util.Set<Material> getPlaceableBlocks() { return placeableBlocks; }

        public boolean isBreakable(Material m) {
            return breakableBlocks.contains(m);
        }

        public boolean isPlaceable(Material m) {
            return placeableBlocks.contains(m);
        }
    }
    public String getKitDisplayName(String kitId) {
        Kit kit = kits.get(kitId);
        if (kit == null) return "§e§l" + kitId; // fallback falls kit fehlt
        return kit.getDisplayName(); // ist schon colored
    }

    public void loadCustomLayoutsFromFile() {
        playerKitLayouts.clear();

        // make sure players.yml is loaded from disk
        plugin.getConfigManager().reloadPlayersConfig();

        var cfg = plugin.getConfigManager().getPlayersConfig();

        // UUIDs at root
        for (String playerIdStr : cfg.getKeys(false)) {
            if (!isValidUUID(playerIdStr)) continue;

            UUID playerId = UUID.fromString(playerIdStr);
            String path = playerIdStr + ".kitLayouts";

            ConfigurationSection layoutsSection = cfg.getConfigurationSection(path);
            if (layoutsSection == null) continue;

            Map<String, ItemStack[]> layouts = new HashMap<>();

            for (String kitId : layoutsSection.getKeys(false)) {
                ConfigurationSection kitSection = layoutsSection.getConfigurationSection(kitId);
                if (kitSection == null) continue;

                ItemStack[] layout = new ItemStack[36];

                for (String slotStr : kitSection.getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(slotStr);
                        if (slot < 0 || slot >= 36) continue;

                        ItemStack it = kitSection.getItemStack(slotStr);
                        layout[slot] = (it == null || it.getType() == Material.AIR) ? null : it.clone();
                    } catch (NumberFormatException ignored) {}
                }

                // only store if something is inside
                boolean any = false;
                for (ItemStack it : layout) {
                    if (it != null && it.getType() != Material.AIR) { any = true; break; }
                }
                if (any) layouts.put(kitId, layout);
            }

            if (!layouts.isEmpty()) {
                playerKitLayouts.put(playerId, layouts);
            }
        }
    }



}
