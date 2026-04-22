package dev.duels.managers;

import dev.duels.DuelsPlugin;
import dev.duels.objects.Arena;
import dev.duels.objects.BlockVector;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;

import java.util.*;

public class ArenaManager {

    private final DuelsPlugin plugin;
    private final Map<String, Arena> arenas = new HashMap<>();
    private final Map<String, Arena> availableArenas = new HashMap<>();
    private Location spawnLocation;

    public ArenaManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadArenas() {
        arenas.clear();
        availableArenas.clear();

        if (plugin.getConfigManager().getMainConfig().contains("spawn")) {
            spawnLocation = plugin.getConfigManager().getMainConfig().getLocation("spawn");
        }

        var arenaCfg = plugin.getConfigManager().getArenaConfig();
        if (arenaCfg == null || !arenaCfg.contains("arenas")) return;

        var arenasSec = arenaCfg.getConfigurationSection("arenas");
        if (arenasSec == null) return;

        for (String arenaName : arenasSec.getKeys(false)) {
            Arena arena = new Arena(arenaName);
            String path = "arenas." + arenaName;

            // ===== LOCATIONS =====
            if (arenaCfg.isString(path + ".spawn1")) {
                arena.setSpawn1(stringToLoc(arenaCfg.getString(path + ".spawn1")));
            } else if (arenaCfg.contains(path + ".spawn1")) {
                arena.setSpawn1(arenaCfg.getLocation(path + ".spawn1"));
            }

            if (arenaCfg.isString(path + ".spawn2")) {
                arena.setSpawn2(stringToLoc(arenaCfg.getString(path + ".spawn2")));
            } else if (arenaCfg.contains(path + ".spawn2")) {
                arena.setSpawn2(arenaCfg.getLocation(path + ".spawn2"));
            }

            if (arenaCfg.isString(path + ".corner1")) {
                arena.setCorner1(stringToLoc(arenaCfg.getString(path + ".corner1")));
            } else if (arenaCfg.contains(path + ".corner1")) {
                arena.setCorner1(arenaCfg.getLocation(path + ".corner1"));
            }

            if (arenaCfg.isString(path + ".corner2")) {
                arena.setCorner2(stringToLoc(arenaCfg.getString(path + ".corner2")));
            } else if (arenaCfg.contains(path + ".corner2")) {
                arena.setCorner2(arenaCfg.getLocation(path + ".corner2"));
            }

            // 🔥 KIT LADEN
            if (arenaCfg.contains(path + ".kit")) {
                arena.setKit(arenaCfg.getString(path + ".kit"));
            }

            // Snapshot
            loadArenaSnapshot(arena);

            arenas.put(arenaName, arena);
            availableArenas.put(arenaName, arena);

            plugin.getLogger().info("Loaded arena: " + arenaName +
                    " (kit=" + arena.getKit() + ")");
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
        return loc.getWorld().getName() + "," +
                loc.getX() + "," + loc.getY() + "," + loc.getZ() + "," +
                loc.getYaw() + "," + loc.getPitch();
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
        String path = "arenas." + arena.getName();

        var cfg = plugin.getConfigManager().getArenaConfig();

        cfg.set(path + ".spawn1", locToString(arena.getSpawn1()));
        cfg.set(path + ".spawn2", locToString(arena.getSpawn2()));
        cfg.set(path + ".corner1", locToString(arena.getCorner1()));
        cfg.set(path + ".corner2", locToString(arena.getCorner2()));

        // 🔥 KIT SPEICHERN
        cfg.set(path + ".kit", arena.getKit());

        saveArenaSnapshot(arena);
        plugin.getConfigManager().saveArenaConfig();
    }

    // 🔥 NEU: MIT KIT FILTER
    public Arena getRandomAvailableArena(String kit) {
        List<Arena> available = new ArrayList<>();

        for (Arena arena : arenas.values()) {
            if (!arena.isInUse()
                    && arena.hasSnapshot()
                    && arena.getSpawn1() != null
                    && arena.getSpawn2() != null
                    && arena.getCorner1() != null
                    && arena.getCorner2() != null) {

                if (kit == null || (arena.getKit() != null && arena.getKit().equalsIgnoreCase(kit))) {
                    available.add(arena);
                }
            }
        }

        if (available.isEmpty()) return null;
        return available.get(new Random().nextInt(available.size()));
    }

    // ALT bleibt für Kompatibilität
    public Arena getRandomAvailableArena() {
        return getRandomAvailableArena(null);
    }

    // 🔥 OPTIONAL COMMAND SUPPORT
    public void setArenaKit(String arenaName, String kit) {
        Arena arena = arenas.get(arenaName);
        if (arena == null) return;

        arena.setKit(kit);
        saveArena(arena);
    }

    // ===== REST BLEIBT UNVERÄNDERT =====
}
