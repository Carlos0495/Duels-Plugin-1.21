package dev.duels.listeners;

import dev.duels.DuelsPlugin;
import dev.duels.managers.HotbarManager;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * Schützt die Plugin-Hotbar vor Verschieben/Wegwerfen ohne andere Items
 * (z.B. aus GUIs/Truhen) zu blockieren. Greift NUR in der Lobby-Welt
 * (Welt mit Plugin-Spawn) und nur für Spieler die nicht im Duel/FFA sind.
 *
 * <p>Erkennung "Hotbar-Item" passiert über den PersistentDataContainer-Tag
 * {@code hotbar_action}, den {@link HotbarManager} bei jedem Item setzt.
 * Dadurch sind beliebige andere Items (von Truhen, GUI-Drops, /give) frei
 * beweglich.</p>
 */
public class HotbarLockListener implements Listener {

    private final DuelsPlugin plugin;

    public HotbarLockListener(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Lock ist nur aktiv wenn Spieler in der Lobby-Welt ist und nicht im Duel/Creative. */
    private boolean lockActive(Player player) {
        if (player == null) return false;
        if (plugin.getDuelManager().isInDuel(player.getUniqueId())) return false;
        if (plugin.getPartyFFAManager() != null
                && plugin.getPartyFFAManager().isParticipant(player.getUniqueId())) return false;
        if (player.getGameMode() == GameMode.CREATIVE) return false;
        return plugin.getPlayerManager().isInLobbyWorld(player);
    }

    /** True, falls das Item ein Plugin-Hotbar-Item ist (per PDC-Tag erkannt). */
    private boolean isHotbarItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        HotbarManager hm = plugin.getHotbarManager();
        if (hm == null) return false;
        String action = hm.readAction(stack);
        return action != null && !action.isEmpty();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!lockActive(player)) return;
        // In der Lobby-Welt sollen Spieler keine Items aufheben (sonst füllen
        // sie ihre Hotbar mit zufälligem Müll). Drop-Itemize regelt der User
        // ggf. über andere Plugins. Hier strikt blocken.
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!lockActive(player)) return;
        // Nur wenn das gedropte Item ein Hotbar-Item ist, blocken.
        if (isHotbarItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!lockActive(player)) return;

        ItemStack current = event.getCurrentItem();

        // Hotkey-Swap (Drücken einer Zifferntaste): swappt Item zwischen
        // Cursor-Slot und Hotbar-Slot. Wenn das Hotbar-Ziel ein gelocktes
        // Item enthält, blocken.
        if (event.getHotbarButton() >= 0) {
            ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
            if (isHotbarItem(hotbarItem) || isHotbarItem(current)) {
                event.setCancelled(true);
                return;
            }
        }

        // Klick AUF ein Hotbar-Item (Pickup, Move, Shift-Click, Throw etc.)
        if (isHotbarItem(current)) {
            event.setCancelled(true);
            return;
        }

        // Items aus Cursor/anderswo werden in einen Hotbar-Slot mit Hotbar-
        // Item gelegt? — durch obigen Check schon abgedeckt (current wäre
        // das gelockte Item). Nur Spezialfall: SWAP_WITH_CURSOR auf einen
        // Hotbar-Slot mit gelocktem Item ist auch durch current=lockedItem
        // abgedeckt.

        // Drop-Aktionen über InventoryClick (Q über Item draufgehalten).
        if (event.getAction() == InventoryAction.DROP_ALL_SLOT
                || event.getAction() == InventoryAction.DROP_ONE_SLOT) {
            if (isHotbarItem(current)) {
                event.setCancelled(true);
            }
        }

        // SWAP_OFFHAND (F-Drücken auf ein Item im Inventar-GUI) — würde das
        // Item in die Offhand verschieben. Hotbar-Items dürfen weder rein
        // noch raus aus der Offhand.
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            ItemStack offhand = player.getInventory().getItemInOffHand();
            if (isHotbarItem(current) || isHotbarItem(offhand)) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Blockt das Drücken der F-Taste (Swap Main-Offhand) wenn entweder das
     * aktuelle Hauptitem oder das Offhand-Item ein Hotbar-Lock-Item ist.
     * Damit kann der Spieler die Hotbar-Items nicht in die Offhand legen.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!lockActive(player)) return;
        if (isHotbarItem(event.getMainHandItem()) || isHotbarItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!lockActive(player)) return;

        // Drag nur blocken, wenn ein Slot betroffen ist, der ein Hotbar-Item
        // enthält (sonst beliebiges Drag-Splitting in Truhen / GUIs erlaubt).
        for (int rawSlot : event.getRawSlots()) {
            ItemStack slotItem = event.getView().getItem(rawSlot);
            if (isHotbarItem(slotItem)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!lockActive(player)) return;

        // In Creative greift der normale ClickEvent kaum; hier nur wenn
        // ein Hotbar-Item überschrieben würde.
        if (isHotbarItem(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }
}
