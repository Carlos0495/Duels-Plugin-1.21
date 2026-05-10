package dev.duels.managers;

import dev.duels.DuelsPlugin;
import dev.duels.objects.Arena;
import dev.duels.objects.BlockVector;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

public class ArenaManager {

    private final DuelsPlugin plugin;
    private final Map<String, Arena> arenas = new HashMap<>();
    private final Map<String, Arena> availableArenas = new HashMap<>();
    private Location spawnLocation;
    /**
     * Gespeicherte Spawn-Kodierung "world,x,y,z,yaw,pitch". Wird benötigt, wenn
     * die Spawn-Welt beim Plugin-Enable noch nicht geladen ist (z.B. Multiverse-
     * Welten die erst später geladen werden). In dem Fall ist {@link #spawnLocation}
     * vorerst {@code null}; beim {@code WorldLoadEvent} wird {@link #tryResolveSpawn()}
     * nachträglich die Location berechnen.
     */
    private String pendingSpawnString;

    /** Eigener Spawn für Party-FFA (alle gegen alle in einer Arena). */
    private Location partyFFASpawn;
    private String pendingPartyFFASpawnString;

    public ArenaManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadArenas() {
        arenas.clear();
        availableArenas.clear();

        loadSpawnFromConfig();
        loadPartyFFASpawnFromConfig();

        var arenaCfg = plugin.getConfigManager().getArenaConfig();
        if (arenaCfg == null || !arenaCfg.contains("arenas")) return;

        var arenasSec = arenaCfg.getConfigurationSection("arenas");
        if (arenasSec == null) return;

        for (String arenaName : arenasSec.getKeys(false)) {
            Arena arena = new Arena(arenaName);
            String path = "arenas." + arenaName;

            // --- Load positions (supports BOTH formats) ---
            // Format A (old): Bukkit serialized Location section
            // Format B (new/pretty): "world,x,y,z,yaw,pitch" string

            // spawn1
            if (arenaCfg.isString(path + ".spawn1")) {
                arena.setSpawn1(stringToLoc(arenaCfg.getString(path + ".spawn1")));
            } else if (arenaCfg.contains(path + ".spawn1")) {
                arena.setSpawn1(arenaCfg.getLocation(path + ".spawn1"));
            }

            // spawn2
            if (arenaCfg.isString(path + ".spawn2")) {
                arena.setSpawn2(stringToLoc(arenaCfg.getString(path + ".spawn2")));
            } else if (arenaCfg.contains(path + ".spawn2")) {
                arena.setSpawn2(arenaCfg.getLocation(path + ".spawn2"));
            }

            // corner1
            if (arenaCfg.isString(path + ".corner1")) {
                arena.setCorner1(stringToLoc(arenaCfg.getString(path + ".corner1")));
            } else if (arenaCfg.contains(path + ".corner1")) {
                arena.setCorner1(arenaCfg.getLocation(path + ".corner1"));
            }

            // corner2
            if (arenaCfg.isString(path + ".corner2")) {
                arena.setCorner2(stringToLoc(arenaCfg.getString(path + ".corner2")));
            } else if (arenaCfg.contains(path + ".corner2")) {
                arena.setCorner2(arenaCfg.getLocation(path + ".corner2"));
            }

            // Allowed kits (empty list / missing = alle Kits erlaubt)
            if (arenaCfg.isList(path + ".allowedKits")) {
                arena.setAllowedKits(arenaCfg.getStringList(path + ".allowedKits"));
            }

            // Snapshot load (your existing method)
            loadArenaSnapshot(arena);

            arenas.put(arenaName, arena);
            availableArenas.put(arenaName, arena);

            plugin.getLogger().info("Loaded arena: " + arenaName
                    + " (spawns=" + (arena.getSpawn1() != null && arena.getSpawn2() != null)
                    + ", corners=" + (arena.getCorner1() != null && arena.getCorner2() != null)
                    + ", snapshot=" + arena.hasSnapshot() + ")");
        }
    }

    private void loadArenaSnapshot(Arena arena) {
        String path = "arenas." + arena.getName() + ".snapshot";
        if (!plugin.getConfigManager().getArenaConfig().contains(path + ".blocks")) return;

        String worldName = plugin.getConfigManager().getArenaConfig().getString(path + ".world");
        if (worldName == null) return;

        arena.setSnapshotWorld(worldName);

        int minX = plugin.getConfigManager().getArenaConfig().getInt(path + ".minX");
        int minY = plugin.getConfigManager().getArenaConfig().getInt(path + ".minY");
        int minZ = plugin.getConfigManager().getArenaConfig().getInt(path + ".minZ");
        int maxX = plugin.getConfigManager().getArenaConfig().getInt(path + ".maxX");
        int maxY = plugin.getConfigManager().getArenaConfig().getInt(path + ".maxY");
        int maxZ = plugin.getConfigManager().getArenaConfig().getInt(path + ".maxZ");

        arena.setSnapshotBounds(minX, minY, minZ, maxX, maxY, maxZ);
        arena.getOriginalBlocks().clear();

        List<String> list = plugin.getConfigManager().getArenaConfig().getStringList(path + ".blocks");
        for (String line : list) {
            String[] parts = line.split("\\|", 2);
            if (parts.length != 2) continue;

            String[] xyz = parts[0].split(",", 3);
            if (xyz.length != 3) continue;

            try {
                int x = Integer.parseInt(xyz[0]);
                int y = Integer.parseInt(xyz[1]);
                int z = Integer.parseInt(xyz[2]);

                BlockData bd = Bukkit.createBlockData(parts[1]);
                arena.getOriginalBlocks().put(new BlockVector(x, y, z), bd);
            } catch (Exception ignored) {}
        }
    }
    private String locToString(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return loc.getWorld().getName() + ","
                + loc.getX() + "," + loc.getY() + "," + loc.getZ() + ","
                + loc.getYaw() + "," + loc.getPitch();
    }

    private Location stringToLoc(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",", 6);
        if (p.length < 4) return null;

        var world = Bukkit.getWorld(p[0]);
        if (world == null) return null;

        double x = Double.parseDouble(p[1]);
        double y = Double.parseDouble(p[2]);
        double z = Double.parseDouble(p[3]);

        float yaw = (p.length >= 5) ? Float.parseFloat(p[4]) : 0f;
        float pitch = (p.length >= 6) ? Float.parseFloat(p[5]) : 0f;

        return new Location(world, x, y, z, yaw, pitch);
    }

    public void saveArena(Arena arena) {
        // Vor dem Schreiben Disk-Stand laden, damit Hand-Edits an anderen
        // Arenen / Keys in arena.yml erhalten bleiben (Config-Reset-Fix).
        plugin.getConfigManager().reloadArenaConfigFromDisk();
        String path = "arenas." + arena.getName();

        plugin.getConfigManager().getArenaConfig().set(path + ".spawn1", locToString(arena.getSpawn1()));
        plugin.getConfigManager().getArenaConfig().set(path + ".spawn2", locToString(arena.getSpawn2()));
        plugin.getConfigManager().getArenaConfig().set(path + ".corner1", locToString(arena.getCorner1()));
        plugin.getConfigManager().getArenaConfig().set(path + ".corner2", locToString(arena.getCorner2()));

        // Allowed kits
        if (arena.getAllowedKits().isEmpty()) {
            plugin.getConfigManager().getArenaConfig().set(path + ".allowedKits", null);
        } else {
            plugin.getConfigManager().getArenaConfig().set(path + ".allowedKits",
                    new ArrayList<>(arena.getAllowedKits()));
        }

        saveArenaSnapshot(arena);
        plugin.getConfigManager().saveArenaConfig();
    }


    private void saveArenaSnapshot(Arena arena) {
        String path = "arenas." + arena.getName() + ".snapshot";

        if (!arena.hasSnapshot()) {
            plugin.getConfigManager().getArenaConfig().set(path, null);
            return;
        }

        plugin.getConfigManager().getArenaConfig().set(path + ".world", arena.getSnapshotWorld());
        plugin.getConfigManager().getArenaConfig().set(path + ".minX", arena.getSnapshotMinX());
        plugin.getConfigManager().getArenaConfig().set(path + ".minY", arena.getSnapshotMinY());
        plugin.getConfigManager().getArenaConfig().set(path + ".minZ", arena.getSnapshotMinZ());
        plugin.getConfigManager().getArenaConfig().set(path + ".maxX", arena.getSnapshotMaxX());
        plugin.getConfigManager().getArenaConfig().set(path + ".maxY", arena.getSnapshotMaxY());
        plugin.getConfigManager().getArenaConfig().set(path + ".maxZ", arena.getSnapshotMaxZ());

        List<String> list = new ArrayList<>();
        for (Map.Entry<BlockVector, BlockData> entry : arena.getOriginalBlocks().entrySet()) {
            BlockVector v = entry.getKey();
            String data = entry.getValue().getAsString();
            list.add(v.getX() + "," + v.getY() + "," + v.getZ() + "|" + data);
        }
        plugin.getConfigManager().getArenaConfig().set(path + ".blocks", list);
    }

    public Arena getAvailableArena() {
        for (Arena arena : availableArenas.values()) {
            if (!arena.isInUse()) {
                return arena;
            }
        }
        return null;
    }

    public Arena getRandomAvailableArena() {
        return getRandomAvailableArenaForKit(null);
    }

    /**
     * Returns a random fully-configured, free arena that allows the given kit.
     * Pass {@code null} to ignore the kit constraint (legacy behaviour).
     */
    public Arena getRandomAvailableArenaForKit(String kitId) {
        List<Arena> available = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (arena.isInUse()) continue;
            if (!arena.hasSnapshot()) continue;
            if (arena.getSpawn1() == null || arena.getSpawn2() == null) continue;
            if (arena.getCorner1() == null || arena.getCorner2() == null) continue;
            if (kitId != null && !arena.isKitAllowed(kitId)) continue;
            available.add(arena);
        }

        if (available.isEmpty()) {
            // Diagnostik: warum hat keine Arena gepasst? Hilft dem Admin
            // Setup-Probleme zu finden ("freie Arena ist da, queue startet
            // trotzdem nicht").
            logArenaSelectionFailure(kitId);
            return null;
        }
        return available.get(new Random().nextInt(available.size()));
    }

    /** Loggt pro Arena ob sie disqualifiziert wurde und warum. */
    private void logArenaSelectionFailure(String kitId) {
        if (arenas.isEmpty()) {
            plugin.getLogger().warning("Arena selection: no arenas configured at all.");
            return;
        }
        plugin.getLogger().warning("Arena selection failed for kit='" + kitId + "'. Reasons per arena:");
        for (Arena arena : arenas.values()) {
            StringBuilder reason = new StringBuilder();
            if (arena.isInUse()) reason.append("inUse ");
            if (!arena.hasSnapshot()) reason.append("noSnapshot ");
            if (arena.getSpawn1() == null) reason.append("noSpawn1 ");
            if (arena.getSpawn2() == null) reason.append("noSpawn2 ");
            if (arena.getCorner1() == null) reason.append("noCorner1 ");
            if (arena.getCorner2() == null) reason.append("noCorner2 ");
            if (kitId != null && !arena.isKitAllowed(kitId)) reason.append("kitNotAllowed ");
            if (reason.length() == 0) reason.append("ok? (would have matched — race?)");
            plugin.getLogger().warning("  - " + arena.getName() + ": " + reason.toString().trim());
        }
    }

    /**
     * Räumt nach Plugin-Restart alle "inUse"-Flags ab. Falls der Server
     * mitten in einem Duel gecrasht/neu gestartet ist, wären sonst alle
     * betroffenen Arenen für immer als belegt markiert und der User würde
     * "no arena free" sehen obwohl alle frei sind.
     */
    public void resetAllInUseFlags() {
        for (Arena arena : arenas.values()) {
            if (arena.isInUse()) {
                arena.setInUse(false);
                plugin.getLogger().info("Arena '" + arena.getName() + "' inUse flag cleared on startup.");
            }
        }
    }

    public String reserveRandomFreeArenaName() {
        Arena arena = getRandomAvailableArena(); // nutzt deine vorhandene Filter-Logik
        return (arena == null) ? null : arena.getName();
    }

    public void resetArena(Arena arena, Runnable onComplete) {
        if (arena == null || !arena.hasSnapshot()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        org.bukkit.World world = Bukkit.getWorld(arena.getSnapshotWorld());
        if (world == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        // Blocks setzen + Entities (Arrows, gedroppte Items, Crystals, Trides
        // etc.) entfernen — alles sync, weil Bukkit-API main-thread-only.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Map.Entry<BlockVector, org.bukkit.block.data.BlockData> e : arena.getOriginalBlocks().entrySet()) {
                BlockVector v = e.getKey();
                world.getBlockAt(v.getX(), v.getY(), v.getZ()).setBlockData(e.getValue(), false);
            }
            cleanupArenaEntities(world, arena);
            if (onComplete != null) onComplete.run();
        });
    }

    /**
     * Killt alle Nicht-Spieler-Entities (Arrows, gedroppte Items, EnderCrystals,
     * Tridents, ItemFrames-Drops etc.) in der Bounding-Box der Arena. Spieler
     * werden NIE entfernt — ein im Reset noch tp-mäßig drin steckender
     * Spieler bleibt unangetastet.
     */
    private void cleanupArenaEntities(org.bukkit.World world, Arena arena) {
        if (world == null || !arena.hasSnapshot()) return;
        int minX = arena.getSnapshotMinX(), minY = arena.getSnapshotMinY(), minZ = arena.getSnapshotMinZ();
        int maxX = arena.getSnapshotMaxX(), maxY = arena.getSnapshotMaxY(), maxZ = arena.getSnapshotMaxZ();
        // Etwas Padding nach oben/außen, damit Arrows die im Snapshot-Rand
        // stecken auch noch erwischt werden.
        double pad = 2.0;
        for (org.bukkit.entity.Entity e : world.getNearbyEntities(
                new org.bukkit.util.BoundingBox(
                        minX - pad, minY - pad, minZ - pad,
                        maxX + 1 + pad, maxY + 1 + pad, maxZ + 1 + pad))) {
            if (e instanceof org.bukkit.entity.Player) continue;
            try { e.remove(); } catch (Throwable ignored) {}
        }
    }

    public Arena getArena(String name) {
        return arenas.get(name);
    }

    public Arena getArenaAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;

        for (Arena arena : arenas.values()) {
            if (arena.isInArena(loc)) {
                return arena;
            }
        }
        return null;
    }

    public boolean createArena(String name, Location spawn1, Location spawn2, Location corner1, Location corner2) {
        if (arenas.containsKey(name)) return false;

        Arena arena = new Arena(name);
        arena.setSpawn1(spawn1);
        arena.setSpawn2(spawn2);
        arena.setCorner1(corner1);
        arena.setCorner2(corner2);

        // Snapshot erstellen
        captureArenaSnapshot(arena);

        arenas.put(name, arena);
        availableArenas.put(name, arena);

        saveArena(arena);
        return true;
    }

    /**
     * Erfasst den Block-Snapshot der Arena. Wenn die Arena das konfigurierte
     * Volumen-Limit ({@code arena.max-snapshot-blocks}, default 200000)
     * überschreitet, wird das Capture übersprungen — die Arena ist dann
     * "spielbar, aber nicht reset-bar". Das verhindert OOM bei riesigen
     * Build-Arenen.
     */
    private boolean captureArenaSnapshot(Arena arena) {
        arena.getOriginalBlocks().clear();

        Location c1 = arena.getCorner1();
        Location c2 = arena.getCorner2();
        if (c1 == null || c2 == null || c1.getWorld() == null) return false;

        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int minY = Math.min(c1.getBlockY(), c2.getBlockY());
        int maxY = Math.max(c1.getBlockY(), c2.getBlockY());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());

        long volume = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        var main = plugin.getConfigManager().getMainConfig();
        long max = main.getLong("arena.max-snapshot-blocks", 200000L);
        if (max > 0 && volume > max) {
            plugin.getLogger().warning("Arena '" + arena.getName() + "' is too big to snapshot ("
                    + volume + " > " + max + " blocks). Set 'arena.max-snapshot-blocks' higher to allow it.");
            arena.setSnapshotWorld(c1.getWorld().getName());
            arena.setSnapshotBounds(minX, minY, minZ, maxX, maxY, maxZ);
            return false;
        }

        arena.setSnapshotWorld(c1.getWorld().getName());
        arena.setSnapshotBounds(minX, minY, minZ, maxX, maxY, maxZ);

        org.bukkit.World world = c1.getWorld();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    org.bukkit.block.data.BlockData bd = world.getBlockAt(x, y, z).getBlockData();
                    arena.getOriginalBlocks().put(new BlockVector(x, y, z), bd.clone());
                }
            }
        }
        return true;
    }

    public boolean deleteArena(String name) {
        Arena arena = arenas.get(name);
        if (arena == null || arena.isInUse()) return false;

        arenas.remove(name);
        availableArenas.remove(name);
        plugin.getConfigManager().reloadArenaConfigFromDisk();
        plugin.getConfigManager().getArenaConfig().set("arenas." + name, null);
        plugin.getConfigManager().saveArenaConfig();

        return true;
    }

    public List<String> getArenaNames() {
        return new ArrayList<>(arenas.keySet());
    }

    public void setSpawnLocation(Location location) {
        this.spawnLocation = location;
        String s = locToString(location);
        this.pendingSpawnString = s;

        // Reload-before-modify: Hand-Edits an config.yml überleben.
        plugin.getConfigManager().reloadMainConfigFromDisk();
        var main = plugin.getConfigManager().getMainConfig();
        // Neues String-Format schreiben, altes Bukkit-Location-Format entfernen
        // (damit nach einem /setspawn nur noch eine Variante in der config.yml
        // steht; beim nächsten Reload liest loadSpawnFromConfig zuerst den
        // String und ignoriert das alte Format).
        main.set("spawn-string", s);
        if (main.contains("spawn") && !main.isString("spawn")) {
            main.set("spawn", null);
        }
        plugin.saveConfig();
        // Kein saveAllConfigs() hier — das würde players.yml unnötig
        // mitschreiben und keine andere Datei wurde geändert.
    }


    public Location getSpawnLocation() {
        // Lazy-Resolve: wenn Welt beim Plugin-Enable noch nicht geladen war,
        // versuchen wir hier nochmal — die Welt ist jetzt vielleicht da
        // (z.B. weil Multiverse/PlotSquared nach uns initialisiert hat).
        if (spawnLocation == null && pendingSpawnString != null) {
            tryResolveSpawn();
        }
        return spawnLocation;
    }

    /**
     * Wird vom {@code WorldListener} aufgerufen, sobald eine neue Welt geladen
     * wird. Falls der Lobby-Spawn in genau dieser Welt liegt, wird die Location
     * jetzt nachträglich aufgelöst.
     */
    public void onWorldLoaded(String worldName) {
        // Lobby-Spawn
        if (spawnLocation == null && pendingSpawnString != null) {
            String[] parts = pendingSpawnString.split(",", 2);
            if (parts.length >= 1 && parts[0].equalsIgnoreCase(worldName)) {
                tryResolveSpawn();
                if (spawnLocation != null) {
                    plugin.getLogger().info("Resolved lobby spawn in world '" + worldName + "' after world load.");
                }
            }
        }

        // Party-FFA-Spawn — gleiches Lazy-Load-Schema
        if (partyFFASpawn == null && pendingPartyFFASpawnString != null) {
            String[] parts = pendingPartyFFASpawnString.split(",", 2);
            if (parts.length >= 1 && parts[0].equalsIgnoreCase(worldName)) {
                Location loc = stringToLoc(pendingPartyFFASpawnString);
                if (loc != null) {
                    partyFFASpawn = loc;
                    plugin.getLogger().info("Resolved Party-FFA spawn in world '" + worldName + "' after world load.");
                }
            }
        }
    }

    public Location getPartyFFASpawn() {
        if (partyFFASpawn == null && pendingPartyFFASpawnString != null) {
            Location loc = stringToLoc(pendingPartyFFASpawnString);
            if (loc != null) partyFFASpawn = loc;
        }
        return partyFFASpawn;
    }

    public void setPartyFFASpawn(Location location) {
        this.partyFFASpawn = location;
        String s = locToString(location);
        this.pendingPartyFFASpawnString = s;
        plugin.getConfigManager().reloadMainConfigFromDisk();
        var main = plugin.getConfigManager().getMainConfig();
        main.set("party-ffa-spawn-string", s);
        plugin.saveConfig();
    }

    private void loadPartyFFASpawnFromConfig() {
        var main = plugin.getConfigManager().getMainConfig();
        if (!main.isString("party-ffa-spawn-string")) return;
        pendingPartyFFASpawnString = main.getString("party-ffa-spawn-string");
        Location loc = stringToLoc(pendingPartyFFASpawnString);
        if (loc != null) {
            partyFFASpawn = loc;
        } else {
            String worldName = pendingPartyFFASpawnString.split(",", 2)[0];
            plugin.getLogger().warning("Party-FFA spawn world '" + worldName
                    + "' is not loaded yet. Will resolve after world load.");
        }
    }

    private void loadSpawnFromConfig() {
        var main = plugin.getConfigManager().getMainConfig();

        // 1) Neues String-Format (bevorzugt, funktioniert auch wenn Welt
        //    beim Plugin-Enable noch nicht geladen ist).
        if (main.isString("spawn-string")) {
            pendingSpawnString = main.getString("spawn-string");
            tryResolveSpawn();
            return;
        }

        // 2) Altes Bukkit-Location-Format (rückwärtskompatibel). Wenn die Welt
        //    geladen ist, funktioniert getLocation normal. Falls nicht, bauen
        //    wir den String manuell aus der ConfigurationSection und
        //    migrieren beim nächsten /setspawn automatisch.
        if (!main.contains("spawn") || main.isString("spawn")) return;

        try {
            Location legacy = main.getLocation("spawn");
            if (legacy != null && legacy.getWorld() != null) {
                spawnLocation = legacy;
                pendingSpawnString = locToString(legacy);
                // Migrieren: in neues Format überführen, altes Format löschen
                main.set("spawn-string", pendingSpawnString);
                main.set("spawn", null);
                plugin.saveConfig();
                return;
            }
        } catch (Throwable ignored) {}

        // Welt nicht geladen — rekonstruiere pendingSpawnString aus dem
        // ConfigurationSection-Key "world" etc. damit NICHTS verloren geht.
        ConfigurationSection sec = main.getConfigurationSection("spawn");
        if (sec != null) {
            String worldName = sec.getString("world");
            if (worldName != null && !worldName.isEmpty()) {
                double x = sec.getDouble("x");
                double y = sec.getDouble("y");
                double z = sec.getDouble("z");
                double yaw = sec.getDouble("yaw", 0);
                double pitch = sec.getDouble("pitch", 0);
                pendingSpawnString = worldName + "," + x + "," + y + "," + z + "," + yaw + "," + pitch;
                // Migrieren, aber altes Format NICHT entfernen, bis Welt da
                // ist — falls wir die String-Encoding falsch interpretiert
                // haben, wäre sonst Datenverlust möglich.
                main.set("spawn-string", pendingSpawnString);
                plugin.saveConfig();
                tryResolveSpawn();
                if (spawnLocation == null) {
                    plugin.getLogger().warning("Lobby spawn world '" + worldName
                            + "' is not loaded yet. Spawn will be resolved after the world loads.");
                }
            }
        }
    }

    private void tryResolveSpawn() {
        if (pendingSpawnString == null || pendingSpawnString.isEmpty()) return;

        Location loc = stringToLoc(pendingSpawnString);
        if (loc != null) {
            spawnLocation = loc;
            return;
        }

        // Welt nicht geladen — versuchen zu laden
        String worldName = pendingSpawnString.split(",", 2)[0];
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            try {
                World loaded = new WorldCreator(worldName).createWorld();
                if (loaded != null) {
                    Location loc2 = stringToLoc(pendingSpawnString);
                    if (loc2 != null) spawnLocation = loc2;
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not auto-load world '" + worldName
                        + "' for lobby spawn: " + t.getMessage());
            }
        }
    }

    // Füge diese Methoden zur ArenaManager Klasse hinzu:

    public boolean setArenaSpawn1(String arenaName, Location location) {
        Arena arena = arenas.get(arenaName);
        if (arena == null) {
            arena = new Arena(arenaName);
            arenas.put(arenaName, arena);
            availableArenas.put(arenaName, arena);
        }

        arena.setSpawn1(location);
        saveArena(arena);
        return true;
    }

    public boolean setArenaSpawn2(String arenaName, Location location) {
        Arena arena = arenas.get(arenaName);
        if (arena == null) {
            arena = new Arena(arenaName);
            arenas.put(arenaName, arena);
            availableArenas.put(arenaName, arena);
        }

        arena.setSpawn2(location);
        saveArena(arena);
        return true;
    }

    public boolean setArenaCorner1(String arenaName, Location location) {
        Arena arena = arenas.get(arenaName);
        if (arena == null) {
            arena = new Arena(arenaName);
            arenas.put(arenaName, arena);
            availableArenas.put(arenaName, arena);
        }

        arena.setCorner1(location);
        saveArena(arena);
        return true;
    }

    public boolean setArenaCorner2(String arenaName, Location location) {
        Arena arena = arenas.get(arenaName);
        if (arena == null) {
            arena = new Arena(arenaName);
            arenas.put(arenaName, arena);
            availableArenas.put(arenaName, arena);
        }

        arena.setCorner2(location);
        saveArena(arena);
        return true;
    }

    public boolean createArena(String name) {
        Arena arena = arenas.get(name);
        if (arena == null) {
            return false;
        }

        if (arena.getSpawn1() == null || arena.getSpawn2() == null) {
            return false;
        }

        if (arena.getCorner1() == null || arena.getCorner2() == null) {
            return false;
        }

        // Snapshot erstellen
        captureArenaSnapshot(arena);
        saveArena(arena);
        return true;
    }



    public void cleanup() {
        for (Arena arena : arenas.values()) {
            if (arena.isInUse()) {
                arena.setInUse(false);
            }
        }
        availableArenas.putAll(arenas);
    }
}