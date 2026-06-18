package dev.duels.objects;

import org.bukkit.Material;

import java.util.List;

/**
 * Konfigurierbares Hotbar-Item (aus {@code config.yml} unter
 * {@code hotbar.<mode>.<key>}). Jedes Item hat einen Action-Identifier, der
 * beim Rechtsklick vom {@code PlayerListener} gelesen und an den passenden
 * Handler (Duel-GUI, Queue, Party, …) weitergereicht wird.
 */
public class HotbarItem {

    private final String key;
    private final int slot;
    private final Material material;
    private final String displayName;
    private final List<String> lore;
    private final String action;

    public HotbarItem(String key, int slot, Material material, String displayName,
                      List<String> lore, String action) {
        this.key = key;
        this.slot = slot;
        this.material = material;
        this.displayName = displayName;
        this.lore = lore;
        this.action = action;
    }

    public String getKey() { return key; }
    public int getSlot() { return slot; }
    public Material getMaterial() { return material; }
    public String getDisplayName() { return displayName; }
    public List<String> getLore() { return lore; }
    public String getAction() { return action; }
}
