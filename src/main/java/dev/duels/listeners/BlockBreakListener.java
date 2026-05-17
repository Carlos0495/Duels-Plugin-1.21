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

    // ignoreCancelled = false + HIGHEST: läuft AUCH wenn ein Anti-Grief-
    // Plugin den Event schon gecancelt hat. Wenn das Kit die Aktion erlaubt,
    // UN-canceln wir das Event explizit (override des Anti-Grief).
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String kitId = currentKitId(player);
        if (kitId == null) return;
        KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) { event.setCancelled(true); return; }

        if (kit.isBreakable(event.getBlock().getType())) {
            // Erlaubt: cancel zurücknehmen falls ein Anti-Grief-Plugin
            // gecancelt hat. Zusätzlich erzwingen dass der Block-Drop
            // generiert wird — Anti-Grief setzt manchmal setDropItems(false)
            // (User-Bug: "wenn man es breakt dann dropped der block nicht").
            if (event.isCancelled()) event.setCancelled(false);
            try { event.setDropItems(true); } catch (Throwable ignored) {}
            try { event.setExpToDrop(event.getExpToDrop()); } catch (Throwable ignored) {}
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        String kitId = currentKitId(player);
        if (kitId == null) return;
        KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) { event.setCancelled(true); return; }

        if (kit.isPlaceable(event.getBlockPlaced().getType())) {
            if (event.isCancelled()) event.setCancelled(false);
        } else {
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

    // HIGHEST + ignoreCancelled=false: läuft auch wenn Anti-Grief gecancelt
    // hat. Wenn die Explosion in einem aktiven Duel/FFA passiert, un-canceln
    // wir und filtern blockList nach der Kit-Whitelist.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        java.util.Set<Material> union = collectBreakableAt(event.getLocation());
        if (union == null) return; // nicht in Duel/FFA-Arena
        if (event.isCancelled()) event.setCancelled(false);
        event.blockList().removeIf(b -> !union.contains(b.getType()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        java.util.Set<Material> union = collectBreakableAt(event.getBlock().getLocation());
        if (union == null) return;
        if (event.isCancelled()) event.setCancelled(false);
        event.blockList().removeIf(b -> !union.contains(b.getType()));
    }

    /**
     * Liefert die Vereinigung der breakable-Listen aller Duel-/FFA-Sessions
     * deren Arena die gegebene Location enthält (Welt-Match + grobe Distanz).
     * {@code null} = keine aktive Session an dieser Location → Listener
     * greift nicht ein.
     */
    private java.util.Set<Material> collectBreakableAt(org.bukkit.Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        java.util.Set<Material> union = java.util.EnumSet.noneOf(Material.class);
        boolean matched = false;
        for (DuelSession s : plugin.getDuelManager().getAllSessions()) {
            dev.duels.objects.Arena a = plugin.getArenaManager().getArena(s.getArenaName());
            if (a == null) continue;
            if (a.getSpawn1() == null || a.getSpawn1().getWorld() == null) continue;
            if (!a.getSpawn1().getWorld().equals(loc.getWorld())) continue;
            matched = true;
            KitManager.Kit kit = plugin.getKitManager().getKit(s.getKitName());
            if (kit != null) union.addAll(kit.getBreakableBlocks());
        }
        if (plugin.getPartyFFAManager() != null) {
            for (dev.duels.managers.PartyFFAManager.FFASession s :
                    plugin.getPartyFFAManager().getAllSessions()) {
                if (s.reservedArena == null) continue;
                if (s.reservedArena.getSpawn1() == null
                        || s.reservedArena.getSpawn1().getWorld() == null) continue;
                if (!s.reservedArena.getSpawn1().getWorld().equals(loc.getWorld())) continue;
                matched = true;
                KitManager.Kit kit = plugin.getKitManager().getKit(s.kitName);
                if (kit != null) union.addAll(kit.getBreakableBlocks());
            }
        }
        return matched ? union : null;
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
