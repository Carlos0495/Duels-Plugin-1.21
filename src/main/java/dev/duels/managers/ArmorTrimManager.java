package dev.duels.managers;

import dev.duels.DuelsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verwaltet pro-Spieler Armor-Trim-Einstellungen für alle vier Rüstungs-
 * teile (Helm, Chestplate, Leggings, Boots). Werte werden in
 * {@code players.yml} unter {@code players.<uuid>.armor-trim.<piece>.*}
 * persistiert.
 *
 * <p>Trims werden in {@link #applyTrimsToArmor(Player)} auf die gerade
 * angezogene Rüstung des Spielers angewendet — egal welches Material
 * (Diamant/Eisen/etc.). Damit gelten die Trim-Einstellungen automatisch
 * für jedes Kit.</p>
 *
 * <p>Permission: {@code duels.armortrim}. Spieler ohne diese Permission
 * können den Editor nicht öffnen und haben keine Trims auf der Rüstung
 * (auch wenn alte Werte in players.yml stehen).</p>
 */
public class ArmorTrimManager {

    public enum Piece {
        HELMET("helmet"),
        CHESTPLATE("chestplate"),
        LEGGINGS("leggings"),
        BOOTS("boots");

        private final String key;
        Piece(String key) { this.key = key; }
        public String key() { return key; }
    }

    // Trim-Patterns in 1.21 (von Registry abrufbar, hier als feste Liste
    // für den Cycle-Editor und sichere Fallbacks).
    private static final List<String> PATTERN_NAMES = Arrays.asList(
            "bolt", "coast", "dune", "eye", "flow", "host",
            "raiser", "rib", "sentry", "shaper", "silence", "snout",
            "spire", "tide", "vex", "ward", "wayfinder", "wild");

    // Trim-Materials in 1.21
    private static final List<String> MATERIAL_NAMES = Arrays.asList(
            "amethyst", "copper", "diamond", "emerald", "gold",
            "iron", "lapis", "netherite", "quartz", "redstone");

    public static final String PERMISSION = "duels.armortrim";

    public static List<String> getPatternNames() { return PATTERN_NAMES; }
    public static List<String> getMaterialNames() { return MATERIAL_NAMES; }

    private final DuelsPlugin plugin;

    public ArmorTrimManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- Persistence ----------

    private String path(UUID uuid, Piece p, String field) {
        return "players." + uuid + ".armor-trim." + p.key() + "." + field;
    }

    /**
     * Gibt das gespeicherte Trim-Pattern für ein Rüstungsteil zurück
     * (z.B. "silence"). Wenn keine Einstellung vorhanden ist, gibt
     * leeren String zurück.
     */
    public String getTrim(UUID uuid, Piece piece) {
        return plugin.getConfigManager().getPlayersConfig()
                .getString(path(uuid, piece, "trim"), "");
    }

    public String getMaterial(UUID uuid, Piece piece) {
        return plugin.getConfigManager().getPlayersConfig()
                .getString(path(uuid, piece, "material"), "");
    }

    public void setTrim(UUID uuid, Piece piece, String trimName) {
        if (trimName == null || trimName.isEmpty()) {
            plugin.getConfigManager().getPlayersConfig().set(path(uuid, piece, "trim"), null);
        } else {
            plugin.getConfigManager().getPlayersConfig().set(path(uuid, piece, "trim"), trimName.toLowerCase());
        }
        plugin.getConfigManager().savePlayersConfig();
    }

    public void setMaterial(UUID uuid, Piece piece, String materialName) {
        if (materialName == null || materialName.isEmpty()) {
            plugin.getConfigManager().getPlayersConfig().set(path(uuid, piece, "material"), null);
        } else {
            plugin.getConfigManager().getPlayersConfig().set(path(uuid, piece, "material"), materialName.toLowerCase());
        }
        plugin.getConfigManager().savePlayersConfig();
    }

    public void clearPiece(UUID uuid, Piece piece) {
        plugin.getConfigManager().getPlayersConfig().set(path(uuid, piece, "trim"), null);
        plugin.getConfigManager().getPlayersConfig().set(path(uuid, piece, "material"), null);
        plugin.getConfigManager().savePlayersConfig();
    }

    /**
     * Liefert das nächste Pattern in der Cycle-Reihenfolge. Wenn kein
     * gültiger Wert gesetzt ist, kehrt das erste Element zurück. Letztes
     * Element → Cycle endet bei "leer/kein Trim" (für Remove-Funktion).
     */
    public String cyclePattern(String current, int direction) {
        return cycleList(PATTERN_NAMES, current, direction);
    }

    public String cycleMaterial(String current, int direction) {
        return cycleList(MATERIAL_NAMES, current, direction);
    }

    private String cycleList(List<String> list, String current, int direction) {
        if (list.isEmpty()) return "";
        // Aktueller Index: -1 = "leer", 0..n-1 = Pattern/Material
        int idx = -1;
        if (current != null && !current.isEmpty()) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).equalsIgnoreCase(current)) { idx = i; break; }
            }
        }
        int total = list.size() + 1; // +1 für "leer/keinen"
        int newIdx;
        if (direction >= 0) {
            newIdx = (idx + 1 + 1) % total - 1; // -1 entspricht "leer"
        } else {
            newIdx = (idx + total) % total - 1;
            if (newIdx < -1) newIdx = list.size() - 1;
        }
        if (newIdx < 0) return "";
        return list.get(newIdx);
    }

    // ---------- Application ----------

    /**
     * Wendet die gespeicherten Trims auf die aktuell vom Spieler getragene
     * Rüstung an (Helm/Chestplate/Leggings/Boots). Wenn der Spieler keine
     * Permission hat, geschieht nichts.
     */
    public void applyTrimsToArmor(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!player.hasPermission(PERMISSION)) return;

        PlayerInventory inv = player.getInventory();
        UUID uuid = player.getUniqueId();

        applyTrimToItem(inv.getHelmet(),     getTrim(uuid, Piece.HELMET),     getMaterial(uuid, Piece.HELMET),     inv, Piece.HELMET);
        applyTrimToItem(inv.getChestplate(), getTrim(uuid, Piece.CHESTPLATE), getMaterial(uuid, Piece.CHESTPLATE), inv, Piece.CHESTPLATE);
        applyTrimToItem(inv.getLeggings(),   getTrim(uuid, Piece.LEGGINGS),   getMaterial(uuid, Piece.LEGGINGS),   inv, Piece.LEGGINGS);
        applyTrimToItem(inv.getBoots(),      getTrim(uuid, Piece.BOOTS),      getMaterial(uuid, Piece.BOOTS),      inv, Piece.BOOTS);

        player.updateInventory();
    }

    private void applyTrimToItem(ItemStack item, String pattern, String material,
                                 PlayerInventory inv, Piece piece) {
        if (item == null || item.getType().isAir()) return;
        if (!(item.getItemMeta() instanceof ArmorMeta meta)) return;

        if (pattern == null || pattern.isEmpty() || material == null || material.isEmpty()) {
            // Kein Trim gesetzt: existierende Trims auf dem Kit-Item
            // bleiben — wir entfernen sie NICHT, weil Admins sie evtl.
            // bewusst pro Kit gesetzt haben.
            return;
        }

        TrimPattern p = resolvePattern(pattern);
        TrimMaterial m = resolveMaterial(material);
        if (p == null || m == null) return;

        meta.setTrim(new ArmorTrim(m, p));
        item.setItemMeta(meta);

        switch (piece) {
            case HELMET     -> inv.setHelmet(item);
            case CHESTPLATE -> inv.setChestplate(item);
            case LEGGINGS   -> inv.setLeggings(item);
            case BOOTS      -> inv.setBoots(item);
        }
    }

    public static TrimPattern resolvePattern(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            NamespacedKey key = NamespacedKey.minecraft(name.toLowerCase());
            return Registry.TRIM_PATTERN.get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    public static TrimMaterial resolveMaterial(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            NamespacedKey key = NamespacedKey.minecraft(name.toLowerCase());
            return Registry.TRIM_MATERIAL.get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    // Capitalize-helper für Display-Namen
    public static String displayName(String raw) {
        if (raw == null || raw.isEmpty()) return "None";
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    /**
     * Statische Helfer: liefert eine Map "displayName -> registry key" für UI-Listen.
     */
    public static Map<String, String> getAllPatterns() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String p : PATTERN_NAMES) map.put(displayName(p), p);
        return map;
    }

    public static Map<String, String> getAllMaterials() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String m : MATERIAL_NAMES) map.put(displayName(m), m);
        return map;
    }
}
