package dev.duels.listeners;

import dev.duels.DuelsPlugin;
import dev.duels.managers.KitManager;
import dev.duels.objects.DuelSession;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.block.Block;

/**
 * Erzwingt die kit-spezifische Whitelist {@code kits.<id>.breakable-blocks}
 * für Block-Break / Block-Place / Explosionen während eines Duels.
 *
 * <ul>
 *   <li>Spieler im Duel: nur Materialien aus der Whitelist seines Kits dürfen
 *       gebrochen / gesetzt werden. Leere Whitelist = nichts erlaubt.</li>
 *   <li>Spieler NICHT im Duel: keine Einschränkung durch dieses Listener
 *       (andere Plugins / Permissions regeln Lobby-Schutz).</li>
 *   <li>Crystal-Explosionen / Bett-Explosionen: nur Whitelist-Blöcke der
 *       aktiven Duel-Session bleiben in {@code blockList()}; alles andere
 *       wird vor dem Schaden entfernt.</li>
 * </ul>
 */
public class BlockBreakListener implements Listener {

    private final DuelsPlugin plugin;

    public BlockBreakListener(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String kitId = currentKitId(player);
        if (kitId == null) return;
        KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) { event.setCancelled(true); return; }

        if (!kit.isBreakable(event.getBlock().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        String kitId = currentKitId(player);
        if (kitId == null) return;
        KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) { event.setCancelled(true); return; }

        if (!kit.isPlaceable(event.getBlockPlaced().getType())) {
            event.setCancelled(true);
        }
    }

    /**
     * Liefert das aktive Kit eines Spielers, falls er entweder im Duel oder
     * in einer Party-FFA-Session ist. Sonst {@code null} (= Listener nicht
     * eingreifen, andere Plugins/Permissions regeln Lobby-Schutz).
     */
    private String currentKitId(Player player) {
        DuelSession s = plugin.getDuelManager().getDuelSession(player.getUniqueId());
        if (s != null) return s.getKitName();
        var ffa = plugin.getPartyFFAManager().getSession(player.getUniqueId());
        if (ffa != null) return ffa.kitName;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        // Filter Block-Liste nach erlaubten Materialien aller laufenden Duels
        // (rein additive Vereinigung aller aktiven breakable-Listen). Wenn
        // niemand duelt, lassen wir das Server-Default unverändert.
        if (event.blockList().isEmpty()) return;
        java.util.Set<Material> union = collectActiveBreakable();
        if (union == null) return; // niemand im Duel -> nicht eingreifen
        event.blockList().removeIf(b -> !union.contains(b.getType()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.blockList().isEmpty()) return;
        java.util.Set<Material> union = collectActiveBreakable();
        if (union == null) return;
        event.blockList().removeIf(b -> !union.contains(b.getType()));
    }

    private boolean isAllowed(DuelSession session, Material mat) {
        String kitId = session.getKitName();
        if (kitId == null) return false;
        KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) return false;
        return kit.isBreakable(mat);
    }

    /**
     * Sammelt die Vereinigung aller breakable-Sets der gerade aktiven Duel-
     * Sessions. {@code null} bedeutet "keine Duel aktiv" (= Listener nicht
     * eingreifen).
     */
    private java.util.Set<Material> collectActiveBreakable() {
        java.util.Collection<DuelSession> active = plugin.getDuelManager().getAllSessions();
        if (active == null || active.isEmpty()) return null;
        java.util.Set<Material> union = java.util.EnumSet.noneOf(Material.class);
        for (DuelSession s : active) {
            KitManager.Kit kit = plugin.getKitManager().getKit(s.getKitName());
            if (kit != null) union.addAll(kit.getBreakableBlocks());
        }
        return union;
    }
}
