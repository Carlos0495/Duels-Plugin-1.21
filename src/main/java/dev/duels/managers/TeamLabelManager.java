package dev.duels.managers;

import dev.duels.DuelsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Verwaltet die [T1]/[T2]-Schwebe-Labels über den Köpfen von Team-Match-Spielern.
 *
 * <p>Bukkit-Scoreboard-Teams sind unzuverlässig, sobald ein TAB-Plugin
 * (NEZNAMY-TAB etc.) {@code scoreboard-teams: enabled: true} hat — TAB
 * überschreibt unsere Teams und das [T1]/[T2]-Prefix verschwindet überm
 * Kopf. Workaround: ein {@link TextDisplay} pro Spieler, gemountet als
 * Passagier des Spielers (folgt automatisch). Der TextDisplay zeigt den
 * Team-Tag und ist unabhängig von Scoreboard-Teams + TAB.
 *
 * <p>Der Label erscheint NUR überm Kopf des Spielers — nicht im
 * Tab. Match-Ende → alle Labels werden entfernt.
 */
public class TeamLabelManager {

    private final DuelsPlugin plugin;
    private final Map<UUID, UUID> labels = new HashMap<>(); // player → text-display

    public TeamLabelManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Spawnt das Team-Label überm Kopf des Spielers. */
    public void spawnLabel(Player player, int teamNum) {
        if (player == null || !player.isOnline()) return;
        removeLabel(player.getUniqueId());

        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
        String t1Sym = cfg.getString("status.team1-symbol", "&b[T1]").replace("&", "§").trim();
        String t2Sym = cfg.getString("status.team2-symbol", "&c[T2]").replace("&", "§").trim();
        String text = teamNum == 1 ? t1Sym : t2Sym;

        Location loc = player.getLocation().clone();
        try {
            TextDisplay td = player.getWorld().spawn(loc, TextDisplay.class, e -> {
                e.text(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().deserialize(text));
                e.setBillboard(Display.Billboard.CENTER);
                e.setSeeThrough(true);
                e.setShadowed(false);
                e.setDefaultBackground(false);
                e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                e.setViewRange(64f);
                e.setPersistent(false);
                // Über den Kopf heben (Player-Eye-Höhe ≈ 1.62, Hat-Slot ≈ 2.2)
                e.setTransformation(new Transformation(
                        new Vector3f(0f, 1.0f, 0f),
                        new AxisAngle4f(0f, 0f, 0f, 0f),
                        new Vector3f(1f, 1f, 1f),
                        new AxisAngle4f(0f, 0f, 0f, 0f)));
            });
            // Auf den Spieler mounten — TextDisplay folgt automatisch.
            try { player.addPassenger(td); } catch (Throwable ignored) {}
            labels.put(player.getUniqueId(), td.getUniqueId());
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not spawn team label for "
                    + player.getName() + ": " + t.getMessage());
        }
    }

    /** Entfernt das Team-Label eines Spielers. */
    public void removeLabel(UUID playerId) {
        UUID labelId = labels.remove(playerId);
        if (labelId == null) return;
        org.bukkit.entity.Entity ent = Bukkit.getEntity(labelId);
        if (ent != null) {
            try { ent.remove(); } catch (Throwable ignored) {}
        }
    }

    /** Entfernt alle Labels (z.B. onDisable). */
    public void clearAll() {
        for (UUID labelId : labels.values()) {
            org.bukkit.entity.Entity ent = Bukkit.getEntity(labelId);
            if (ent != null) {
                try { ent.remove(); } catch (Throwable ignored) {}
            }
        }
        labels.clear();
    }
}
