package dev.duels;

import dev.duels.commands.*;
import dev.duels.guis.GUIManager;
import dev.duels.listeners.*;
import dev.duels.managers.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class DuelsPlugin extends JavaPlugin {

    private static DuelsPlugin instance;
    private ConfigManager configManager;
    private DuelManager duelManager;
    private ArenaManager arenaManager;
    private QueueManager queueManager;
    private KitManager kitManager;
    private PlayerManager playerManager;
    private ScoreboardManager scoreboardManager;
    private GUIManager guiManager;
    private HotbarManager hotbarManager;
    private GuiConfig guiConfig;
    private PartyManager partyManager;
    private PartyFFAManager partyFFAManager;
    private SpectateManager spectateManager;
    private TeamLabelManager teamLabelManager;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        // Manager initialisieren
        configManager = new ConfigManager(this);
        arenaManager = new ArenaManager(this);
        kitManager = new KitManager(this);
        playerManager = new PlayerManager(this);
        scoreboardManager = new ScoreboardManager(this);
        queueManager = new QueueManager(this);
        duelManager = new DuelManager(this);
        guiManager = new GUIManager(this);
        hotbarManager = new HotbarManager(this);
        guiConfig = new GuiConfig(this);
        partyManager = new PartyManager(this);
        partyFFAManager = new PartyFFAManager(this);
        spectateManager = new SpectateManager(this);
        teamLabelManager = new TeamLabelManager(this);

        // Konfigurationen laden
        configManager.loadAllConfigs();
        configManager.reloadPlayersConfig();
        kitManager.loadCustomLayoutsFromFile();
        hotbarManager.loadHotbarConfig();
        guiConfig.load();
        partyManager.loadConfig();

        Bukkit.getScheduler().runTaskLater(this, () -> arenaManager.loadArenas(), 40L);
        kitManager.loadKits();
        playerManager.loadPlayerData();





        // Events registrieren
        registerListeners();

        // Commands registrieren
        registerCommands();

        // Tasks starten
        startTasks();

        // PlaceholderAPI optional einhaken (für TAB-Plugin etc. — User-Bug:
        // "Zeichen hinter dem namen werden von dem TAB plugin überschrieben").
        // Erst direkt registrieren; falls schon eine alte Instanz existiert
        // (z.B. nach Plugin-Reload), wird sie vorher abgemeldet.
        registerDuelsPlaceholders();

        // Direkte TAB-Integration: registriert %duels_status% direkt im
        // NEZNAMY-TAB-PlaceholderManager. Hintergrund: in manchen Setups
        // findet TAB unsere PAPI-Expansion nicht (TAB-Format zeigt
        // %luckperms_prefix% korrekt, %duels_status% bleibt leer obwohl
        // /papi parse korrekt funktioniert). Mit dieser direkten
        // Registrierung wertet TAB %duels_status% selbst aus und ignoriert
        // PAPI komplett.
        registerTabPlaceholders();

        // Falls PAPI/TAB selbst noch nicht geladen sind, mehrere Retries
        // (Plugin-Load-Order kann variieren — manche TAB-Versionen brauchen
        // länger zum Start). Zusätzlich nach TAB-Reload (durch /tab reload)
        // muss neu registriert werden, weil TAB seine eigene Placeholder-
        // Map dann zurücksetzt.
        long[] retries = { 40L, 200L, 600L, 1200L }; // 2s, 10s, 30s, 60s
        for (long delay : retries) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                registerDuelsPlaceholders();
                registerTabPlaceholders();
            }, delay);
        }



        String CYAN = "\u001B[36m";
        String GREEN = "\u001B[32m";
        String GRAY = "\u001B[90m";
        String BOLD = "\u001B[1m";
        String RESET = "\u001B[0m";

        Bukkit.getLogger().info(GRAY + BOLD + "╔════════════════════════════╗" + RESET);
        Bukkit.getLogger().info(GRAY + BOLD + "║       " + CYAN + "Duels Plugin" + GRAY + "         ║" + RESET);
        Bukkit.getLogger().info(GRAY + BOLD + "║    " + GREEN + "Successfully Enabled" + GRAY + "    ║" + RESET);
        Bukkit.getLogger().info(GRAY + BOLD + "╚════════════════════════════╝" + RESET);

    }

    @Override
    public void onDisable() {
        // Alles sauber herunterfahren
        duelManager.cleanupAll();
        arenaManager.cleanup();
        queueManager.cleanup();
        if (partyManager != null) partyManager.cleanupAll();
        if (teamLabelManager != null) teamLabelManager.clearAll();
        playerManager.saveAllData();
        configManager.saveAllConfigs();

        getLogger().info("Duels plugin disabled!");
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DuelListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ArenaListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GUIListener(this), this);
        Bukkit.getPluginManager().registerEvents(new HotbarLockListener(this), this);
        Bukkit.getPluginManager().registerEvents(new WorldListener(this), this);
        Bukkit.getPluginManager().registerEvents(new dev.duels.listeners.BlockBreakListener(this), this);
    }

    private void registerCommands() {
        getCommand("duels").setExecutor(new MainCommand(this));
        getCommand("duel").setExecutor(new DuelCommand(this));
        getCommand("queue").setExecutor(new QueueCommand(this));
        getCommand("kits").setExecutor(new KitsCommand(this));
        getCommand("previewkit").setExecutor(new PreviewKitCommand(this));
        getCommand("stats").setExecutor(new StatsCommand(this));
        getCommand("settings").setExecutor(new SettingsCommand(this));
        getCommand("arena").setExecutor(new ArenaCommand(this));
        getCommand("setspawn").setExecutor(new SetSpawnCommand(this));
        getCommand("setkills").setExecutor(new SetStatsCommand(this));
        getCommand("setdeaths").setExecutor(new SetStatsCommand(this));
        getCommand("setwins").setExecutor(new SetStatsCommand(this));
        getCommand("setlosses").setExecutor(new SetStatsCommand(this));
        if (getCommand("setcoins") != null) getCommand("setcoins").setExecutor(new SetStatsCommand(this));
        // Add-Varianten: addieren statt überschreiben (User-Wunsch: /coinsadd etc.)
        AddStatsCommand addStats = new AddStatsCommand(this);
        for (String c : new String[]{"killsadd", "deathsadd", "winsadd", "lossesadd", "coinsadd"}) {
            if (getCommand(c) != null) getCommand(c).setExecutor(addStats);
        }
        getCommand("kit").setExecutor(new KitCommand(this));
        getCommand("ping").setExecutor(new PingCommand(this));
        getCommand("fly").setExecutor(new FlyCommand(this));
        getCommand("spawn").setExecutor(new SpawnCommand(this));
        getCommand("accept").setExecutor(new AcceptCommand(this));

        if (getCommand("spectate") != null) {
            SpectateCommand spec = new SpectateCommand(this);
            getCommand("spectate").setExecutor(spec);
            getCommand("spectate").setTabCompleter(spec);
        }

        if (getCommand("dkit") != null) {
            KitEditCommand ke = new KitEditCommand(this);
            getCommand("dkit").setExecutor(ke);
            getCommand("dkit").setTabCompleter(ke);
        }

        PartyCommand partyCmd = new PartyCommand(this);
        if (getCommand("party") != null) {
            getCommand("party").setExecutor(partyCmd);
            getCommand("party").setTabCompleter(partyCmd);
        }
    }

    private void startTasks() {
        // Scoreboard Update Task
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            playerManager.updateAllScoreboards();
            duelManager.updateDuelTimers();
            queueManager.checkQueueMatches();
        }, 0L, 20L);

        // GUI Update Task
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            guiManager.updateOpenGUIs();
        }, 0L, 20L);

        // Lobby-Inventar permanent clear + Tab-Suffix Update (5x pro Sekunde,
        // User-Wunsch "öfter clearen" — verhindert dass per Drag-and-Drop oder
        // /give erhaltene Items in der Lobby liegen bleiben).
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (org.bukkit.entity.Player pl : Bukkit.getOnlinePlayers()) {
                try {
                    playerManager.clearNonHotbarItems(pl);
                    playerManager.updateTabName(pl);
                } catch (Throwable ignored) {}
            }
        }, 4L, 4L);
    }

    public static DuelsPlugin getInstance() {
        return instance;
    }

    // Getter für alle Manager
    public ConfigManager getConfigManager() { return configManager; }
    public DuelManager getDuelManager() { return duelManager; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public QueueManager getQueueManager() { return queueManager; }
    public KitManager getKitManager() { return kitManager; }
    public PlayerManager getPlayerManager() { return playerManager; }
    public ScoreboardManager getScoreboardManager() { return scoreboardManager; }
    public GUIManager getGuiManager() { return guiManager; }
    public HotbarManager getHotbarManager() { return hotbarManager; }
    public GuiConfig getGuiConfig() { return guiConfig; }
    public PartyManager getPartyManager() { return partyManager; }
    public PartyFFAManager getPartyFFAManager() { return partyFFAManager; }
    public SpectateManager getSpectateManager() { return spectateManager; }
    public TeamLabelManager getTeamLabelManager() { return teamLabelManager; }

    /**
     * Registriert die PAPI-Expansion {@code %duels_*%}. Wird in
     * {@link #onEnable()} einmalig + nach 2 s erneut aufgerufen
     * (für den Fall, dass PAPI noch nicht geladen ist).
     */
    public void registerDuelsPlaceholders() {
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                getLogger().warning("PlaceholderAPI not present — %duels_status% will NOT work in TAB.");
                return;
            }
            // Wenn unsere Expansion bereits registriert ist, abmelden
            // (z.B. nach Plugin-Reload), sonst kollidiert register().
            try {
                me.clip.placeholderapi.expansion.PlaceholderExpansion existing =
                        me.clip.placeholderapi.PlaceholderAPIPlugin.getInstance()
                                .getLocalExpansionManager().getExpansion("duels");
                if (existing != null) existing.unregister();
            } catch (Throwable ignored) {}
            boolean ok = new dev.duels.placeholders.DuelsPlaceholders(this).register();
            if (ok) {
                getLogger().info("Registered PlaceholderAPI expansion '%duels_*%' (identifier: duels).");
            } else {
                getLogger().warning("PlaceholderAPI.register() returned false for 'duels' expansion.");
            }
        } catch (Throwable t) {
            getLogger().warning("Could not register PlaceholderAPI expansion: " + t.getMessage());
        }
    }

    /**
     * Registriert {@code %duels_status%} direkt im NEZNAMY-TAB-Plugin
     * (PlaceholderManager). Wenn TAB nicht installiert ist, passiert nichts.
     */
    public void registerTabPlaceholders() {
        try {
            if (Bukkit.getPluginManager().getPlugin("TAB") == null) return;
            dev.duels.placeholders.TabHook.unregister();
            dev.duels.placeholders.TabHook.register(this);
        } catch (NoClassDefFoundError e) {
            // TAB-API-Klassen fehlen — vermutlich alte TAB-Version. PAPI-
            // Fallback bleibt aktiv.
            getLogger().warning("TAB plugin detected but TAB-API classes unavailable: "
                    + e.getMessage() + " — falling back to PlaceholderAPI.");
        } catch (Throwable t) {
            getLogger().warning("Could not register TAB placeholders: " + t.getMessage());
        }
    }

    // Prefix konfigurierbar via config.yml -> prefix: "..." (mit & für Farben)
    public String getPrefix() {
        if (configManager == null || configManager.getMainConfig() == null) {
            return "§9§lᴅᴜᴇʟѕ §8| §7";
        }
        String raw = configManager.getMainConfig().getString("prefix", "&9&lᴅᴜᴇʟѕ &8| &7");
        return raw.replace('&', '§');
    }
}