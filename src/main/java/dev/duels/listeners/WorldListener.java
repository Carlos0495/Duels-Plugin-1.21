package dev.duels.listeners;

import dev.duels.DuelsPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Reagiert darauf, dass eine Welt (z.B. von Multiverse/PlotSquared) erst NACH
 * unserem {@code onEnable} geladen wird. In dem Fall konnte der Lobby-Spawn
 * nicht aufgelöst werden; wir holen das hier nach, sobald die Welt da ist.
 */
public class WorldListener implements Listener {

    private final DuelsPlugin plugin;

    public WorldListener(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        String worldName = event.getWorld().getName();
        plugin.getArenaManager().onWorldLoaded(worldName);
    }
}
