package dev.duels.managers;

import dev.duels.DuelsPlugin;
import dev.duels.objects.HotbarItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lädt Hotbar-Item-Definitionen aus {@code config.yml} und baut daraus die
 * Inventar-Hotbar für die jeweiligen Modi:
 * <ul>
 *     <li>{@link #MODE_LOBBY} – Standard-Hotbar (Queue, Stats, Visibility, Settings, Party-Create)</li>
 *     <li>{@link #MODE_PARTY_LEADER} – für Party-Leader (Party-Menü, Invite, Leave, Public)</li>
 *     <li>{@link #MODE_PARTY_MEMBER} – für normale Party-Mitglieder (Leave, Info)</li>
 * </ul>
 *
 * <p>Jeder Konfig-Key darf fehlen; fehlt ein Modus komplett, fällt der Manager
 * auf eingebaute Defaults zurück, damit das Plugin auch bei leerer/veralteter
 * {@code config.yml} direkt lauffähig ist.</p>
 *
 * <p>Items werden beim Aufbau mit einem {@link NamespacedKey} getaggt
 * ({@code hotbar_action}); der {@code PlayerListener} liest diesen Tag und
 * dispatcht deterministisch — unabhängig von den (vom User editierbaren)
 * Display-Namen.</p>
 */
public class HotbarManager {

    public static final String MODE_LOBBY = "lobby";
    public static final String MODE_PARTY_LEADER = "party-leader";
    public static final String MODE_PARTY_MEMBER = "party-member";

    public static final String ACTION_CHALLENGE = "CHALLENGE";
    public static final String ACTION_QUEUE_DYNAMIC = "QUEUE_DYNAMIC";
    public static final String ACTION_STATS = "STATS";
    public static final String ACTION_VISIBILITY = "VISIBILITY";
    public static final String ACTION_SETTINGS = "SETTINGS";
    public static final String ACTION_PARTY_CREATE = "PARTY_CREATE";
    public static final String ACTION_PARTY_MENU = "PARTY_MENU";
    public static final String ACTION_PARTY_INVITE = "PARTY_INVITE";
    public static final String ACTION_PARTY_LEAVE = "PARTY_LEAVE";
    public static final String ACTION_PARTY_PUBLIC = "PARTY_PUBLIC";
    public static final String ACTION_PARTY_INFO = "PARTY_INFO";

    private final DuelsPlugin plugin;
    private final NamespacedKey actionKey;
    private final Map<String, List<HotbarItem>> modes = new LinkedHashMap<>();

    public HotbarManager(DuelsPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "hotbar_action");
    }

    public NamespacedKey getActionKey() { return actionKey; }

    /** Einmalig beim {@code onEnable} aufzurufen. Schreibt Defaults in die Config, wenn nötig. */
    public void loadHotbarConfig() {
        modes.clear();
        var main = plugin.getConfigManager().getMainConfig();

        boolean dirty = false;
        if (!main.isConfigurationSection("hotbar.lobby")) {
            writeDefaultsLobby(main);
            dirty = true;
        }
        if (!main.isConfigurationSection("hotbar.party-leader")) {
            writeDefaultsPartyLeader(main);
            dirty = true;
        }
        if (!main.isConfigurationSection("hotbar.party-member")) {
            writeDefaultsPartyMember(main);
            dirty = true;
        }
        // Migration: Settings-Item in Party-Modi nachrüsten (für Fly-Toggle &
        // andere Settings in der Party). Nur einfügen wenn noch nicht da und
        // der Slot frei ist.
        if (addSettingsToPartyMode(main, "hotbar.party-leader.")) dirty = true;
        if (addSettingsToPartyMode(main, "hotbar.party-member.")) dirty = true;
        if (dirty) plugin.saveConfig();

        modes.put(MODE_LOBBY, readMode(main, MODE_LOBBY));
        modes.put(MODE_PARTY_LEADER, readMode(main, MODE_PARTY_LEADER));
        modes.put(MODE_PARTY_MEMBER, readMode(main, MODE_PARTY_MEMBER));
    }

    private List<HotbarItem> readMode(org.bukkit.configuration.file.FileConfiguration main, String mode) {
        List<HotbarItem> list = new ArrayList<>();
        ConfigurationSection sec = main.getConfigurationSection("hotbar." + mode);
        if (sec == null) return list;

        for (String key : sec.getKeys(false)) {
            ConfigurationSection item = sec.getConfigurationSection(key);
            if (item == null) continue;

            int slot = item.getInt("slot", -1);
            if (slot < 0 || slot > 8) continue;

            Material mat;
            try {
                mat = Material.valueOf(item.getString("material", "PAPER").toUpperCase());
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid material for hotbar." + mode + "." + key
                        + ": " + item.getString("material") + " (using PAPER)");
                mat = Material.PAPER;
            }

            String displayName = item.getString("name", "");
            List<String> lore = item.getStringList("lore");
            String action = item.getString("action", "");

            list.add(new HotbarItem(key, slot, mat, displayName, lore, action));
        }
        return list;
    }

    public List<HotbarItem> getItems(String mode) {
        return modes.getOrDefault(mode, new ArrayList<>());
    }

    /** Leert Slots 0–8 und setzt die Items des angegebenen Modus. */
    public void applyMode(Player player, String mode) {
        if (player == null) return;
        // Hotbar-/Party-Items NUR in der Lobby-Welt setzen. Sonst würde z.B.
        // eine Party-Einladung/Annahme während eines Duel/FFA-Matches das
        // aktive Kit mit den Party-Items überschreiben und die Runde
        // zerstören (User-Bug: "man bekommt die party items erst wenn man in
        // der lobby welt ist"). Wenn der Spieler zurück in die Lobby kommt,
        // setzt setupPlayerInventory() die korrekte Hotbar (inkl. Party-Mode).
        if (plugin.getPlayerManager() != null
                && !plugin.getPlayerManager().isInLobbyWorld(player)) {
            return;
        }
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) inv.setItem(i, null);

        for (HotbarItem def : getItems(mode)) {
            ItemStack stack = buildItem(player, def);
            inv.setItem(def.getSlot(), stack);
        }

        // Queue-Item ist dynamisch (Join vs. Leave) – vom PlayerManager gesetzt,
        // nachdem unser Standardlayout steht. Nur im Lobby-Modus relevant.
        if (MODE_LOBBY.equals(mode)) {
            plugin.getPlayerManager().refreshQueueSlotItem(player);
        }

        player.updateInventory();
    }

    public ItemStack buildItem(Player holder, HotbarItem def) {
        ItemStack stack;
        if (def.getMaterial() == Material.PLAYER_HEAD) {
            stack = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) stack.getItemMeta();
            if (meta != null && holder != null) {
                meta.setOwningPlayer(holder);
                applyCommonMeta(meta, def);
                stack.setItemMeta(meta);
            }
        } else {
            stack = new ItemStack(def.getMaterial());
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                applyCommonMeta(meta, def);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    private void applyCommonMeta(ItemMeta meta, HotbarItem def) {
        if (def.getDisplayName() != null && !def.getDisplayName().isEmpty()) {
            meta.setDisplayName(color(def.getDisplayName()));
        }
        if (def.getLore() != null && !def.getLore().isEmpty()) {
            List<String> colored = new ArrayList<>(def.getLore().size());
            for (String l : def.getLore()) colored.add(color(l));
            meta.setLore(colored);
        }
        if (def.getAction() != null && !def.getAction().isEmpty()) {
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, def.getAction());
        }
    }

    private String color(String s) {
        if (s == null) return "";
        return s.replace('&', '§');
    }

    /** Lies den Action-Tag eines beliebigen ItemStack. {@code null} wenn kein Tag. */
    public String readAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
    }

    // ---- Defaults ----

    private void writeDefaultsLobby(org.bukkit.configuration.file.FileConfiguration main) {
        String base = "hotbar.lobby.";
        main.set(base + "challenge.slot", 0);
        main.set(base + "challenge.material", "DIAMOND_SWORD");
        main.set(base + "challenge.name", "&aᴄʜᴀʟʟᴇɴɢᴇ");
        main.set(base + "challenge.lore", Arrays.asList(
                "&7Hit a &cPlayer &7to &achallenge &7them",
                "&7Right-click to &ajoin the &aqueue"));
        main.set(base + "challenge.action", ACTION_CHALLENGE);

        main.set(base + "stats.slot", 1);
        main.set(base + "stats.material", "PAPER");
        main.set(base + "stats.name", "&bѕᴛᴀᴛѕ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "stats.lore", Arrays.asList("&7See your stats or see the leaderboard"));
        main.set(base + "stats.action", ACTION_STATS);

        main.set(base + "party.slot", 2);
        main.set(base + "party.material", "NAME_TAG");
        main.set(base + "party.name", "&dᴄʀᴇᴀᴛᴇ ᴘᴀʀᴛʏ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "party.lore", Arrays.asList(
                "&7Open a party and invite players.",
                "&7Use /party for commands."));
        main.set(base + "party.action", ACTION_PARTY_CREATE);

        // slot 4 wird dynamisch von refreshQueueSlotItem belegt
        main.set(base + "queue.slot", 4);
        main.set(base + "queue.material", "PLAYER_HEAD");
        main.set(base + "queue.name", "&aᴊᴏɪɴ ʟᴀѕᴛ ǫᴜᴇᴜᴇ ᴀɢᴀɪɴ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "queue.lore", Arrays.asList("&7Here you can join the same Queue again."));
        main.set(base + "queue.action", ACTION_QUEUE_DYNAMIC);

        main.set(base + "visibility.slot", 7);
        main.set(base + "visibility.material", "GREEN_DYE");
        main.set(base + "visibility.name", "&aᴘʟᴀʏᴇʀ ᴠɪѕɪʙɪʟɪᴛʏ ᴏɴ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "visibility.lore", Arrays.asList("&7Change the Player visibility."));
        main.set(base + "visibility.action", ACTION_VISIBILITY);

        main.set(base + "settings.slot", 8);
        main.set(base + "settings.material", "REPEATER");
        main.set(base + "settings.name", "&cѕᴇᴛᴛɪɴɢѕ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "settings.lore", Arrays.asList("&7All kind of settings"));
        main.set(base + "settings.action", ACTION_SETTINGS);
    }

    private void writeDefaultsPartyLeader(org.bukkit.configuration.file.FileConfiguration main) {
        String base = "hotbar.party-leader.";

        main.set(base + "party-menu.slot", 0);
        main.set(base + "party-menu.material", "NETHER_STAR");
        main.set(base + "party-menu.name", "&dᴘᴀʀᴛʏ ᴍᴇɴᴜ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "party-menu.lore", Arrays.asList(
                "&7Open the party duel menu.",
                "&7Choose between 1v1, FFA, or Team vs Team."));
        main.set(base + "party-menu.action", ACTION_PARTY_MENU);

        main.set(base + "party-invite.slot", 1);
        main.set(base + "party-invite.material", "WRITABLE_BOOK");
        main.set(base + "party-invite.name", "&aɪɴᴠɪᴛᴇ ᴘʟᴀʏᴇʀ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "party-invite.lore", Arrays.asList(
                "&7Use /party invite <player> to invite someone."));
        main.set(base + "party-invite.action", ACTION_PARTY_INVITE);

        main.set(base + "party-public.slot", 2);
        main.set(base + "party-public.material", "BEACON");
        main.set(base + "party-public.name", "&6ᴘᴜʙʟɪᴄ ᴘᴀʀᴛʏ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "party-public.lore", Arrays.asList(
                "&7Toggle public party.",
                "&7When public, it's announced in chat and anyone can join."));
        main.set(base + "party-public.action", ACTION_PARTY_PUBLIC);

        main.set(base + "party-info.slot", 4);
        main.set(base + "party-info.material", "PAPER");
        main.set(base + "party-info.name", "&bᴘᴀʀᴛʏ ɪɴꜰᴏ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "party-info.lore", Arrays.asList(
                "&7Show your current party members."));
        main.set(base + "party-info.action", ACTION_PARTY_INFO);

        main.set(base + "party-leave.slot", 8);
        main.set(base + "party-leave.material", "BARRIER");
        main.set(base + "party-leave.name", "&cᴅɪѕʙᴀɴᴅ ᴘᴀʀᴛʏ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "party-leave.lore", Arrays.asList(
                "&7Disband your party."));
        main.set(base + "party-leave.action", ACTION_PARTY_LEAVE);

        main.set(base + "settings.slot", 7);
        main.set(base + "settings.material", "REPEATER");
        main.set(base + "settings.name", "&cѕᴇᴛᴛɪɴɢѕ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "settings.lore", Arrays.asList("&7All kind of settings (e.g. fly)"));
        main.set(base + "settings.action", ACTION_SETTINGS);
    }

    private void writeDefaultsPartyMember(org.bukkit.configuration.file.FileConfiguration main) {
        String base = "hotbar.party-member.";

        main.set(base + "party-info.slot", 0);
        main.set(base + "party-info.material", "PAPER");
        main.set(base + "party-info.name", "&bᴘᴀʀᴛʏ ɪɴꜰᴏ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "party-info.lore", Arrays.asList(
                "&7Show your current party members."));
        main.set(base + "party-info.action", ACTION_PARTY_INFO);

        main.set(base + "party-leave.slot", 8);
        main.set(base + "party-leave.material", "BARRIER");
        main.set(base + "party-leave.name", "&cʟᴇᴀᴠᴇ ᴘᴀʀᴛʏ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "party-leave.lore", Arrays.asList("&7Leave your party."));
        main.set(base + "party-leave.action", ACTION_PARTY_LEAVE);

        main.set(base + "settings.slot", 7);
        main.set(base + "settings.material", "REPEATER");
        main.set(base + "settings.name", "&cѕᴇᴛᴛɪɴɢѕ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "settings.lore", Arrays.asList("&7All kind of settings (e.g. fly)"));
        main.set(base + "settings.action", ACTION_SETTINGS);
    }

    /**
     * Fügt einem bereits existierenden Party-Hotbar-Modus das Settings-Item
     * hinzu, falls es fehlt. Sucht einen freien Slot (bevorzugt 7).
     * @return true wenn etwas geändert wurde.
     */
    private boolean addSettingsToPartyMode(org.bukkit.configuration.file.FileConfiguration main, String base) {
        ConfigurationSection sec = main.getConfigurationSection(base.substring(0, base.length() - 1));
        if (sec == null) return false; // wird von writeDefaults* abgedeckt
        // Schon vorhanden? (per Action SETTINGS prüfen, Name egal)
        for (String key : sec.getKeys(false)) {
            ConfigurationSection item = sec.getConfigurationSection(key);
            if (item != null && ACTION_SETTINGS.equalsIgnoreCase(item.getString("action", ""))) {
                return false;
            }
        }
        // Belegte Slots sammeln.
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (String key : sec.getKeys(false)) {
            ConfigurationSection item = sec.getConfigurationSection(key);
            if (item != null) used.add(item.getInt("slot", -1));
        }
        int slot = !used.contains(7) ? 7 : -1;
        if (slot < 0) {
            for (int i = 0; i <= 8; i++) { if (!used.contains(i)) { slot = i; break; } }
        }
        if (slot < 0) return false; // Hotbar voll, nichts tun
        main.set(base + "settings.slot", slot);
        main.set(base + "settings.material", "REPEATER");
        main.set(base + "settings.name", "&cѕᴇᴛᴛɪɴɢѕ &7(ʀɪɢʜᴛᴄʟɪᴄᴋ)");
        main.set(base + "settings.lore", Arrays.asList("&7All kind of settings (e.g. fly)"));
        main.set(base + "settings.action", ACTION_SETTINGS);
        return true;
    }
}
