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

        Material type = event.getBlock().getType();
        dev.duels.objects.Arena arena = findArenaAt(event.getBlock().getLocation());
        boolean allowed = isBreakAllowed(kitId, kit, arena, type, event.getBlock());

        if (allowed) {
            // Erlaubt: cancel zurücknehmen falls ein Anti-Grief-Plugin
            // gecancelt hat. Zusätzlich erzwingen dass der Block-Drop
            // generiert wird — Anti-Grief setzt manchmal setDropItems(false)
            // (User-Bug: "wenn man es breakt dann dropped der block nicht").
            if (event.isCancelled()) event.setCancelled(false);
            try { event.setDropItems(true); } catch (Throwable ignored) {}
            try { event.setExpToDrop(event.getExpToDrop()); } catch (Throwable ignored) {}
            // Selbst platzierter Block wird abgebaut → aus dem Tracking nehmen,
            // damit der Arena-Reset ihn nicht doppelt behandelt.
            if (arena != null) {
                dev.duels.objects.BlockVector v = new dev.duels.objects.BlockVector(
                        event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
                arena.removePlayerPlacedBlock(v);
            }
        } else {
            event.setCancelled(true);
        }
    }

    /**
     * Entscheidet, ob {@code player} (mit {@code kit}) den Block abbauen darf.
     *
     * <p>Custom-Kits: man darf IMMER selbst platzierte Blöcke abbauen, plus
     * die in der Arena als breakable markierten Materialien. Beliebige Arena-
     * Blöcke bleiben geschützt.</p>
     *
     * <p>Normale Kits: gilt die Kit-eigene breakable-Liste sowie – je nach
     * Config-Precedence – zusätzlich die Arena-Liste.</p>
     */
    private boolean isBreakAllowed(String kitId, KitManager.Kit kit,
                                   dev.duels.objects.Arena arena, Material type,
                                   org.bukkit.block.Block block) {
        boolean playerPlaced = false;
        boolean arenaBreakable = false;
        if (arena != null) {
            dev.duels.objects.BlockVector v = new dev.duels.objects.BlockVector(
                    block.getX(), block.getY(), block.getZ());
            playerPlaced = arena.isPlayerPlacedBlock(v);
            arenaBreakable = arena.isArenaBreakable(type);
        }

        if (plugin.getKitManager().isCustomKit(kitId)) {
            // Custom-Kit: selbst platziert ODER vom Admin freigegebener Arena-Block.
            return playerPlaced || arenaBreakable;
        }

        // Normale Kits: Precedence-Modus berücksichtigen.
        boolean kitBreakable = kit.isBreakable(type);
        String mode = plugin.getConfigManager().getMainConfig()
                .getString("custom-kits.block-rules.precedence", "MERGE")
                .toUpperCase(java.util.Locale.ROOT);
        boolean arenaResult;
        switch (mode) {
            case "ARENA" -> // Arena-Liste überschreibt: nur Arena zählt, wenn gesetzt.
                arenaResult = (arena != null && !arena.getBreakableBlocks().isEmpty())
                        ? arenaBreakable : kitBreakable;
            case "KIT" -> // Kit-Liste überschreibt: Arena-Liste ignorieren.
                arenaResult = kitBreakable;
            default -> // MERGE: Vereinigung beider Listen.
                arenaResult = kitBreakable || arenaBreakable;
        }
        return arenaResult || playerPlaced;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        String kitId = currentKitId(player);
        if (kitId == null) return;
        KitManager.Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) { event.setCancelled(true); return; }

        // Custom-Kits: ALLE Blöcke dürfen platziert werden (User-Wunsch). Sonst
        // ergäbe es keinen Sinn, dass man die Arena-breakable-Blöcke abbauen,
        // aber nicht (wieder) setzen kann. Jeder platzierte Block wird getrackt
        // und ist damit auch wieder abbaubar + wird beim Arena-Reset entfernt.
        boolean placeAllowed = plugin.getKitManager().isCustomKit(kitId)
                || kit.isPlaceable(event.getBlockPlaced().getType());
        if (placeAllowed) {
            if (event.isCancelled()) event.setCancelled(false);
            // ArenaListener trackt Placements bei NORMAL-Priority. Wenn ein
            // Anti-Grief-Plugin den Event vorher gecancelt hat, läuft der
            // ArenaListener NICHT (ignoreCancelled=true default). Wir un-
            // canceln hier bei HIGHEST → Block wird platziert, aber NICHT
            // in playerPlacedBlocks getrackt. Daher hier nachholen, damit
            // resetArena() den Block entfernt (User-Bug: "geplactes
            // glowstone geht nicht weg").
            dev.duels.objects.Arena arena = findArenaAt(event.getBlock().getLocation());
            if (arena != null) {
                dev.duels.objects.BlockVector v = new dev.duels.objects.BlockVector(
                        event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
                arena.addPlayerPlacedBlock(v);
                // Original-Block auch für den Reset vormerken falls die
                // Position nicht im Snapshot ist (corner-nahe Blöcke).
                if (!arena.getOriginalBlocks().containsKey(v)) {
                    try {
                        arena.getOriginalBlocks().put(v,
                                event.getBlockReplacedState().getBlockData().clone());
                    } catch (Throwable ignored) {}
                }
            }
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
    // hat. Wenn die Explosion in einem aktiven Duel/FFA passiert:
    //   1. Un-canceln (Anti-Grief override).
    //   2. blockList nach Kit-Whitelist filtern.
    //   3. Sphere-Augmentation: vanilla packt blast-resistente Blöcke wie
    //      OBSIDIAN / CRYING_OBSIDIAN nicht in blockList — Crystal-Damage
    //      reicht physikalisch nicht. Wenn der Admin OBSIDIAN aber
    //      explizit in breakable-blocks gesetzt hat, sollen Crystals/
    //      Anchors sie zerstören. Deshalb scannen wir eine kleine Sphäre
    //      um die Explosion und force-destroyen alle Whitelist-Blöcke
    //      darin im nächsten Tick (auch wenn Anti-Grief noch was kapern
    //      will — wir nutzen Block.setType, kein Event).
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        java.util.Set<Material> union = collectBreakableAt(event.getLocation());
        if (union == null) return; // nicht in Duel/FFA-Arena
        if (event.isCancelled()) event.setCancelled(false);
        event.blockList().removeIf(b -> !union.contains(b.getType()));
        // Pre-record für Arena-Reset: jeder Block der durch die Explosion
        // VERSCHWINDET muss als Original getrackt sein, damit resetArena()
        // ihn wiederherstellt. Auch wenn der Snapshot die Stelle nicht
        // abdeckt (zB Arena ohne Corners), funktioniert der Reset jetzt.
        dev.duels.objects.Arena arena = findArenaAt(event.getLocation());
        recordOriginals(arena, event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        java.util.Set<Material> union = collectBreakableAt(event.getBlock().getLocation());
        if (union == null) return;
        if (event.isCancelled()) event.setCancelled(false);
        event.blockList().removeIf(b -> !union.contains(b.getType()));
        dev.duels.objects.Arena arena = findArenaAt(event.getBlock().getLocation());
        recordOriginals(arena, event.blockList());
    }

    private dev.duels.objects.Arena findArenaAt(org.bukkit.Location loc) {
        for (DuelSession s : plugin.getDuelManager().getAllSessions()) {
            dev.duels.objects.Arena a = plugin.getArenaManager().getArena(s.getArenaName());
            if (isLocationInArena(a, loc)) return a;
        }
        if (plugin.getPartyFFAManager() != null) {
            for (dev.duels.managers.PartyFFAManager.FFASession s :
                    plugin.getPartyFFAManager().getAllSessions()) {
                if (isLocationInArena(s.reservedArena, loc)) return s.reservedArena;
            }
        }
        return null;
    }

    private void recordOriginals(dev.duels.objects.Arena arena, java.util.List<org.bukkit.block.Block> blocks) {
        if (arena == null || blocks == null) return;
        for (org.bukkit.block.Block b : blocks) {
            dev.duels.objects.BlockVector v =
                    new dev.duels.objects.BlockVector(b.getX(), b.getY(), b.getZ());
            if (!arena.getOriginalBlocks().containsKey(v)) {
                try { arena.getOriginalBlocks().put(v, b.getBlockData().clone()); }
                catch (Throwable ignored) {}
            }
        }
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
            if (!isLocationInArena(a, loc)) continue;
            matched = true;
            KitManager.Kit kit = plugin.getKitManager().getKit(s.getKitName());
            if (kit != null) union.addAll(kit.getBreakableBlocks());
        }
        if (plugin.getPartyFFAManager() != null) {
            for (dev.duels.managers.PartyFFAManager.FFASession s :
                    plugin.getPartyFFAManager().getAllSessions()) {
                if (!isLocationInArena(s.reservedArena, loc)) continue;
                matched = true;
                KitManager.Kit kit = plugin.getKitManager().getKit(s.kitName);
                if (kit != null) union.addAll(kit.getBreakableBlocks());
            }
        }
        return matched ? union : null;
    }

    /**
     * True wenn die Location plausibel zur Arena gehört. Strategie:
     *   1. Welt muss übereinstimmen.
     *   2. Wenn Corners gesetzt sind: AABB-Check (mit etwas Puffer).
     *   3. Sonst: Distanz zu spawn1/spawn2 muss &lt; 150 sein (großzügig).
     * Damit greift der Listener NICHT in der Lobby, auch wenn Lobby
     * + Arena in der gleichen Welt liegen.
     */
    private boolean isLocationInArena(dev.duels.objects.Arena a, org.bukkit.Location loc) {
        if (a == null || loc == null) return false;
        org.bukkit.Location s1 = a.getSpawn1();
        if (s1 == null || s1.getWorld() == null) return false;
        if (!s1.getWorld().equals(loc.getWorld())) return false;
        org.bukkit.Location c1 = a.getCorner1();
        org.bukkit.Location c2 = a.getCorner2();
        if (c1 != null && c2 != null && c1.getWorld() != null && c2.getWorld() != null
                && c1.getWorld().equals(loc.getWorld())) {
            double minX = Math.min(c1.getX(), c2.getX()) - 8;
            double maxX = Math.max(c1.getX(), c2.getX()) + 8;
            double minY = Math.min(c1.getY(), c2.getY()) - 8;
            double maxY = Math.max(c1.getY(), c2.getY()) + 8;
            double minZ = Math.min(c1.getZ(), c2.getZ()) - 8;
            double maxZ = Math.max(c1.getZ(), c2.getZ()) + 8;
            return loc.getX() >= minX && loc.getX() <= maxX
                && loc.getY() >= minY && loc.getY() <= maxY
                && loc.getZ() >= minZ && loc.getZ() <= maxZ;
        }
        // Fallback: Distanz zu spawn1/spawn2
        double d1 = loc.distanceSquared(s1);
        if (d1 < 150 * 150) return true;
        org.bukkit.Location s2 = a.getSpawn2();
        if (s2 != null && s2.getWorld() != null && s2.getWorld().equals(loc.getWorld())) {
            return loc.distanceSquared(s2) < 150 * 150;
        }
        return false;
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
