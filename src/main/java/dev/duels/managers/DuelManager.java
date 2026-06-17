package dev.duels.managers;

import dev.duels.DuelsPlugin;
import dev.duels.objects.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.*;

public class DuelManager {

    private final DuelsPlugin plugin;
    private final Map<UUID, DuelSession> activeDuels = new HashMap<>();
    private final Map<UUID, DuelRequest> duelRequests = new HashMap<>();
    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<UUID, Location> pendingRoundRespawn = new HashMap<>();
    private final Set<UUID> roundDead = new HashSet<>();
    private final Map<PairKey, AutoSelect> autoSelect = new HashMap<>();
    private final Map<UUID, Long> lastRequestMs = new HashMap<>();
    private static final long REQUEST_COOLDOWN_MS = 10_000; // 10 Sekunden

    private final Map<UUID, PlayerState> savedStates = new HashMap<>();


    private static class PlayerState {
        final org.bukkit.inventory.ItemStack[] contents;
        final org.bukkit.inventory.ItemStack[] armor;
        final float exp;
        final int level;
        final int food;
        final float saturation;
        final double health;
        final org.bukkit.GameMode gameMode;
        final Location location;

        PlayerState(Player p) {
            this.contents = p.getInventory().getContents().clone();
            this.armor = p.getInventory().getArmorContents().clone();
            this.exp = p.getExp();
            this.level = p.getLevel();
            this.food = p.getFoodLevel();
            this.saturation = p.getSaturation();
            this.health = p.getHealth();
            this.gameMode = p.getGameMode();
            this.location = p.getLocation().clone();
        }

        void restore(Player p) {
            p.getInventory().clear();
            p.getInventory().setContents(contents);
            p.getInventory().setArmorContents(armor);
            p.setExp(exp);
            p.setLevel(level);
            p.setFoodLevel(food);
            p.setSaturation(saturation);
            p.setGameMode(gameMode);

            // Effekte weg
            for (PotionEffect e : p.getActivePotionEffects()) {
                p.removePotionEffect(e.getType());
            }

            p.setHealth(Math.min(health, p.getMaxHealth()));

            p.updateInventory();
        }
    }

    private void teleportToSpawnSafe(Player p) {
        if (p == null || !p.isOnline()) return;

        Location spawn = plugin.getArenaManager().getSpawnLocation();
        if (spawn != null) {
            plugin.getPlayerManager().safeTeleport(p, spawn);
            return;
        }

        // Fallback: World Spawn
        Location worldSpawn = p.getWorld().getSpawnLocation();
        if (worldSpawn != null) plugin.getPlayerManager().safeTeleport(p, worldSpawn);
    }

    private static final long AUTOSELECT_TIMEOUT_MS = 20000;

    public DuelManager(DuelsPlugin plugin) {
        this.plugin = plugin;
        // Periodischer Cleanup für abgelaufene Duel-Anfragen — sonst blockiert
        // eine nie akzeptierte Anfrage die reservierte Arena dauerhaft.
        // Timeout konfigurierbar via config.yml: duel-request-timeout-seconds
        // (Default 30s). Läuft jede Sekunde.
        Bukkit.getScheduler().runTaskTimer(plugin, this::expirePendingRequests, 20L, 20L);
    }

    private void expirePendingRequests() {
        int timeoutSec = plugin.getConfigManager().getMainConfig()
                .getInt("duel-request-timeout-seconds", 30);
        if (timeoutSec <= 0) return;
        Iterator<Map.Entry<UUID, DuelRequest>> it = duelRequests.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, DuelRequest> entry = it.next();
            DuelRequest req = entry.getValue();
            if (req == null) { it.remove(); continue; }
            if (req.isExpired(timeoutSec)) {
                // Arena freigeben (war reserviert während des Pending-State)
                if (req.getArenaName() != null) {
                    Arena a = plugin.getArenaManager().getArena(req.getArenaName());
                    if (a != null) a.setInUse(false);
                }
                // Sender + Receiver informieren
                Player sender = Bukkit.getPlayer(req.getSender());
                Player target = Bukkit.getPlayer(entry.getKey());
                if (sender != null && sender.isOnline()) {
                    sender.sendMessage(plugin.getConfigManager().prefixed("duel.your-request-expired",
                            "&7Your duel request to &e{player} &7expired.",
                            java.util.Map.of("player", target != null ? target.getName() : "player")));
                }
                if (target != null && target.isOnline()) {
                    target.sendMessage(plugin.getConfigManager().prefixed("duel.request-from-expired",
                            "&7Duel request from &e{player} &7expired.",
                            java.util.Map.of("player", sender != null ? sender.getName() : "player")));
                }
                it.remove();
            }
        }
    }

    public void startDuel(DuelRequest request) {
        Player player1 = Bukkit.getPlayer(request.getSender());
        Player player2 = Bukkit.getPlayer(request.getTarget());
        if (player1 == null || player2 == null) return;

        // Falls ein Teilnehmer gerade spectatet: Spectate sauber beenden,
        // sonst blockiert onGameModeChange den Wechsel aus SPECTATOR und der
        // Spieler wird nicht ins Duel teleportiert (User-Bug).
        if (plugin.getSpectateManager() != null) {
            plugin.getSpectateManager().clearSpectating(player1);
            plugin.getSpectateManager().clearSpectating(player2);
        }

        savedStates.putIfAbsent(player1.getUniqueId(), new PlayerState(player1));
        savedStates.putIfAbsent(player2.getUniqueId(), new PlayerState(player2));

        Arena arena = null;

        String arenaName = request.getArenaName();
        if (arenaName != null) {
            arena = plugin.getArenaManager().getArena(arenaName);
        }

        if (arena == null) {
            // fallback (shouldn't happen often)
            arena = plugin.getArenaManager().getRandomAvailableArenaForKit(request.getKitName());
            if (arena == null) {
                player1.sendMessage(plugin.getConfigManager().prefixed("duel.no-arena-available", "&cNo available arena for this kit!"));
                player2.sendMessage(plugin.getConfigManager().prefixed("duel.no-arena-available", "&cNo available arena for this kit!"));
                return;
            }
            arena.setInUse(true);
        } else {
            // arena was reserved in addDuelRequest, keep it inUse
            arena.setInUse(true);
        }

        // Duel-Dauer ermitteln: Kit-spezifisch > globaler Default. -1 oder
        // until-death=true heißt "kein Timer" (wird in updateDuelTimers
        // ignoriert).
        int duelTime = plugin.getConfigManager().getMainConfig().getInt("duel-time", 180);
        var kitObj = plugin.getKitManager().getKit(request.getKitName());
        if (kitObj != null) {
            if (kitObj.isUntilDeath() || kitObj.getDurationSeconds() == -1) {
                duelTime = -1;
            } else if (kitObj.getDurationSeconds() > 0) {
                duelTime = kitObj.getDurationSeconds();
            }
        }

        // DuelSession erstellen
        DuelSession session = new DuelSession(
                player1.getUniqueId(),
                player2.getUniqueId(),
                request.getKitName(),
                arena.getName(),
                duelTime,
                request.getBestOf()
        );

        // Session registrieren
        activeDuels.put(player1.getUniqueId(), session);
        activeDuels.put(player2.getUniqueId(), session);

        // Spieler vorbereiten
        preparePlayersForDuel(player1, player2, session, arena);

        // Countdown starten
        startDuelCountdown(player1, player2, session);
    }
    private void clearChat(Player player) {
        for (int i = 0; i < 100; i++) {
            player.sendMessage("");
        }
    }

    private void preparePlayersForDuel(Player p1, Player p2, DuelSession session, Arena arena) {
        // GameMode auf SURVIVAL erzwingen (z.B. falls Spieler vorher
        // spectatet hat). isInDuel ist hier bereits true → onGameModeChange
        // überschreibt das Inventar nicht.
        if (p1.getGameMode() != GameMode.SURVIVAL) p1.setGameMode(GameMode.SURVIVAL);
        if (p2.getGameMode() != GameMode.SURVIVAL) p2.setGameMode(GameMode.SURVIVAL);

        // Inventar leeren
        forceRoundState(p1);
        forceRoundState(p2);

        p1.getInventory().clear();
        p2.getInventory().clear();
        p1.getInventory().setArmorContents(null);
        p2.getInventory().setArmorContents(null);

        // Kits geben
        plugin.getKitManager().giveKit(p1, session.getKitName());
        plugin.getKitManager().giveKit(p2, session.getKitName());

        // Pro-Kit Start-Effekte (Saturation + Auto-Potions).
        plugin.getKitManager().applyKitStartEffects(p1, session.getKitName());
        plugin.getKitManager().applyKitStartEffects(p2, session.getKitName());

        // Teleportieren
        if (arena.getSpawn1() != null && arena.getSpawn2() != null) {
            plugin.getPlayerManager().safeTeleport(p1, arena.getSpawn1());
            plugin.getPlayerManager().safeTeleport(p2, arena.getSpawn2());
        }

        // Tab-Liste anpassen
        plugin.getPlayerManager().applyDuelVisibility(p1, p2);
        // Tablist-Filter (falls aktiv) für alle neu berechnen.
        plugin.getPlayerManager().refreshAllVisibility();


        // Nachrichten senden
        clearChat(p1);
        clearChat(p2);
        String kitDisplay = plugin.getKitManager().getKitDisplayName(session.getKitName());

        // Kit/Arena-Zeile: Map nur anzeigen wenn in config aktiviert.
        String startKitArena = plugin.getConfigManager().isShowMapInRequest()
                ? plugin.getConfigManager().prefixed("duel.kit-arena", "&7Kit: &r{kit} &7| Arena: &b{arena}", java.util.Map.of("kit", kitDisplay, "arena", session.getArenaName()))
                : plugin.getConfigManager().prefixed("duel.kit-only", "&7Kit: &r{kit}", java.util.Map.of("kit", kitDisplay));

        p1.sendMessage(plugin.getConfigManager().prefixed("duel.started", "&aDuel started &7against &c{player}!", java.util.Map.of("player", p2.getName())));
        p1.sendMessage(startKitArena);
        p1.sendMessage(plugin.getConfigManager().prefixed("duel.match-info", "&dMatch: &fBest of {bestof} &7(need {wins} wins)", java.util.Map.of("bestof", String.valueOf(session.getBestOf()), "wins", String.valueOf(session.requiredWins()))));

        p2.sendMessage(plugin.getConfigManager().prefixed("duel.started", "&aDuel started &7against &c{player}!", java.util.Map.of("player", p1.getName())));
        p2.sendMessage(startKitArena);
        p2.sendMessage(plugin.getConfigManager().prefixed("duel.match-info", "&dMatch: &fBest of {bestof} &7(need {wins} wins)", java.util.Map.of("bestof", String.valueOf(session.getBestOf()), "wins", String.valueOf(session.requiredWins()))));
    }

    private void startDuelCountdown(Player p1, Player p2, DuelSession session) {
        frozenPlayers.add(p1.getUniqueId());
        frozenPlayers.add(p2.getUniqueId());

        // Blindness
        p1.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false));
        p2.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false));

        new BukkitRunnable() {
            int time = 3;

            @Override
            public void run() {
                if (time > 0) {
                    p1.sendTitle("§c" + time, "§7Get ready", 0, 20, 0);
                    p2.sendTitle("§c" + time, "§7Get ready", 0, 20, 0);

                    float pitch = time == 3 ? 0.5f : (time == 2 ? 0.8f : 1.2f);
                    p1.playSound(p1.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, pitch);
                    p2.playSound(p2.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, pitch);

                    time--;
                } else {
                    // FIGHT
                    p1.sendTitle("§aFIGHT!", "§7Best of " + session.getBestOf(), 0, 20, 10);
                    p2.sendTitle("§aFIGHT!", "§7Best of " + session.getBestOf(), 0, 20, 10);

                    p1.playSound(p1.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);
                    p2.playSound(p2.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);

                    p1.sendMessage("");
                    p2.sendMessage("");
                    p1.sendMessage(plugin.getConfigManager().prefixed("duel.fight-round", "&a&lFIGHT! &7Round &f#{round}", java.util.Map.of("round", "1")));
                    p2.sendMessage(plugin.getConfigManager().prefixed("duel.fight-round", "&a&lFIGHT! &7Round &f#{round}", java.util.Map.of("round", "1")));

                    frozenPlayers.remove(p1.getUniqueId());
                    frozenPlayers.remove(p2.getUniqueId());

                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    public void handleDuelDeath(Player dead, Player killer, boolean disconnected) {
        UUID deadId = dead.getUniqueId();
        DuelSession session = activeDuels.get(deadId);
        if (session == null) return;

        // Anti-double trigger (round-transition race)
        if (session.isRoundStarting()) return;
        // Anti-double trigger (match already ended, e.g. void death right after
        // the final hit in Bo1 would otherwise re-enter and start a phantom
        // round 2). Once the match is over we ignore further deaths.
        if (session.isMatchEnded()) return;
        session.setRoundStarting(true);

        UUID winnerId = session.getOpponent(deadId);
        if (winnerId == null) {
            session.setRoundStarting(false);
            return;
        }

        Player winner = Bukkit.getPlayer(winnerId);
        if (winner == null || !winner.isOnline()) {
            session.setRoundStarting(false);
            endDuel(deadId, killer, true);
            return;
        }

        // Score update
        if (session.getPlayer1().equals(winnerId)) {
            session.setWinsP1(session.getWinsP1() + 1);
        } else {
            session.setWinsP2(session.getWinsP2() + 1);
        }

        roundDead.add(deadId);

        // Names safe
        Player s1 = Bukkit.getPlayer(session.getPlayer1());
        Player s2 = Bukkit.getPlayer(session.getPlayer2());
        String n1 = (s1 != null) ? s1.getName() : "Player1";
        String n2 = (s2 != null) ? s2.getName() : "Player2";

        String scoreFormat = "§7" + n1 + " §8(§f" + session.getWinsP1() + " §7- §f" + session.getWinsP2() + "§8) §7" + n2;

        winner.sendMessage(plugin.getConfigManager().prefixed("duel.round-won", "&aYou won &7round &f#{round} &8| {score}", java.util.Map.of("round", String.valueOf(session.getRound()), "score", scoreFormat)));
        dead.sendMessage(plugin.getConfigManager().prefixed("duel.round-lost", "&cYou lost &7round &f#{round} &8| {score}", java.util.Map.of("round", String.valueOf(session.getRound()), "score", scoreFormat)));

        // Match over?
        if (session.getWinsP1() >= session.requiredWins() || session.getWinsP2() >= session.requiredWins()) {
            session.setRoundStarting(false);
            // Match-End Win/Lose Title (User-Wunsch). Zeigt großen Titel an
            // beide Spieler.
            java.util.Map<String,String> ph = java.util.Map.of("score", scoreFormat);
            winner.sendTitle(
                plugin.getConfigManager().getMessage("duel.win-title", "§a§lVICTORY", ph),
                plugin.getConfigManager().getMessage("duel.win-subtitle", "§7" + scoreFormat, ph),
                0, 60, 20);
            dead.sendTitle(
                plugin.getConfigManager().getMessage("duel.lose-title", "§c§lDEFEAT", ph),
                plugin.getConfigManager().getMessage("duel.lose-subtitle", "§7" + scoreFormat, ph),
                0, 60, 20);
            winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            dead.playSound(dead.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
            // Flag Match als beendet BEVOR endDuel läuft, damit ein
            // nachfolgender Void-/Fall-/Fire-Tod kein Phantom-Round-Start
            // triggern kann (z.B. Bo1: Verlierer stirbt → Match over → endDuel
            // startet async Arena-Reset; der Gewinner fällt in den Void
            // während des Resets → handleDuelDeath dürfte NICHT eine neue
            // Runde starten).
            session.setMatchEnded(true);
            endDuel(deadId, winner, disconnected);
            return;
        }

        // Zwischen-Runden Title (User-Wunsch): kurz "X hat Runde gewonnen"
        // anzeigen, dann erst nächste Runde starten.
        final String winnerName = winner.getName();
        final int currentRound = session.getRound();
        java.util.Map<String,String> phRound = java.util.Map.of(
                "winner", winnerName,
                "round", String.valueOf(currentRound),
                "score", scoreFormat);
        winner.sendTitle("§a§lRound won!", "§7" + scoreFormat, 0, 40, 10);
        dead.sendTitle(
                plugin.getConfigManager().getMessage("duel.round-lost-title", "§c§lRound lost", phRound),
                plugin.getConfigManager().getMessage("duel.round-lost-subtitle", "§7" + winnerName + " §7won round §f" + currentRound, phRound),
                0, 40, 10);

        // Nächste Runde vorbereiten
        session.setRound(session.getRound() + 1);

        // Pending Respawns SOFORT setzen, damit der Auto-Respawn direkt in
        // die Arena teleportiert (PlayerRespawnEvent wird evtl. bevor der
        // asynchrone Arena-Reset fertig ist ausgelöst).
        Arena arena = plugin.getArenaManager().getArena(session.getArenaName());
        if (arena != null && arena.getSpawn1() != null && arena.getSpawn2() != null) {
            pendingRoundRespawn.put(session.getPlayer1(), arena.getSpawn1());
            pendingRoundRespawn.put(session.getPlayer2(), arena.getSpawn2());
        }

        // 2.5s Verzögerung damit der Zwischen-Runden-Title sichtbar bleibt
        // bevor die nächste Runde anfängt (User-Wunsch).
        Bukkit.getScheduler().runTaskLater(plugin, () -> startNextRound(session), 50L);
    }

    private void startNextRound(DuelSession session) {
        Player p1 = Bukkit.getPlayer(session.getPlayer1());
        Player p2 = Bukkit.getPlayer(session.getPlayer2());

        if (p1 == null || p2 == null) {
            UUID loser = (p1 == null) ? session.getPlayer1() : session.getPlayer2();
            endDuel(loser, null, true);
            return;
        }

        Arena arena = plugin.getArenaManager().getArena(session.getArenaName());
        if (arena == null) {
            endDuelByTimeout(p1, p2);
            return;
        }


        // Arena resetten
        plugin.getArenaManager().resetArena(arena, () -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!isInDuel(p1.getUniqueId()) || !isInDuel(p2.getUniqueId())) return;

                // Sicherstellen dass die pending Respawns gesetzt sind
                if (arena.getSpawn1() != null && arena.getSpawn2() != null) {
                    pendingRoundRespawn.putIfAbsent(session.getPlayer1(), arena.getSpawn1());
                    pendingRoundRespawn.putIfAbsent(session.getPlayer2(), arena.getSpawn2());
                }

                // Lebende Spieler direkt teleportieren & ausstatten.
                // Tote Spieler werden über den PlayerRespawnEvent (pending
                // respawn) teleportiert und bekommen dort ihr Kit.
                prepareRoundPlayer(p1, session);
                prepareRoundPlayer(p2, session);

                // Timer pro Runde zurücksetzen (User-Wunsch: Timeout soll
                // nur die aktuelle Runde beenden, nicht das ganze Match).
                session.setTimeLeft(session.getInitialDuration());

                runRoundCountdown(session, p1, p2);
            });
        });
    }

    private void prepareRoundPlayer(Player player, DuelSession session) {
        if (player == null || !player.isOnline()) return;

        UUID uuid = player.getUniqueId();

        // Toter Spieler: Auto-Respawn + PlayerRespawnEvent übernehmen Teleport + Kit.
        if (roundDead.contains(uuid) || player.isDead()) {
            return;
        }

        Location target = pendingRoundRespawn.get(uuid);
        if (target != null) {
            plugin.getPlayerManager().safeTeleport(player, target);
        }

        forceRoundState(player);
        plugin.getKitManager().giveKit(player, session.getKitName());
        plugin.getKitManager().applyKitStartEffects(player, session.getKitName());
    }

    private void runRoundCountdown(DuelSession session, Player p1, Player p2) {
        frozenPlayers.add(p1.getUniqueId());
        frozenPlayers.add(p2.getUniqueId());

        p1.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false));
        p2.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false));

        new BukkitRunnable() {
            int time = 3;

            @Override
            public void run() {
                if (time > 0) {
                    p1.sendTitle("§c" + time, "§7Get ready", 0, 20, 0);
                    p2.sendTitle("§c" + time, "§7Get ready", 0, 20, 0);

                    float pitch = time == 3 ? 0.5f : (time == 2 ? 0.8f : 1.2f);
                    p1.playSound(p1.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, pitch);
                    p2.playSound(p2.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, pitch);

                    time--;
                } else {
                    p1.sendTitle("§aFIGHT!", "§7Best of " + session.getBestOf(), 0, 20, 10);
                    p2.sendTitle("§aFIGHT!", "§7Best of " + session.getBestOf(), 0, 20, 10);

                    p1.playSound(p1.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);
                    p2.playSound(p2.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);

                    p1.sendMessage("");
                    p2.sendMessage("");
                    p1.sendMessage(plugin.getConfigManager().prefixed("duel.fight-round", "&a&lFIGHT! &7Round &f#{round}", java.util.Map.of("round", String.valueOf(session.getRound()))));
                    p2.sendMessage(plugin.getConfigManager().prefixed("duel.fight-round", "&a&lFIGHT! &7Round &f#{round}", java.util.Map.of("round", String.valueOf(session.getRound()))));

                    frozenPlayers.remove(p1.getUniqueId());
                    frozenPlayers.remove(p2.getUniqueId());
                    roundDead.remove(p1.getUniqueId());
                    roundDead.remove(p2.getUniqueId());
                    session.setRoundStarting(false);

                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    public void endDuel(UUID loserUUID, Player killer, boolean disconnected) {
        DuelSession session = activeDuels.get(loserUUID);
        if (session == null) return;

        UUID winnerUUID = session.getOpponent(loserUUID);

        Player loser = Bukkit.getPlayer(loserUUID);
        Player winner = winnerUUID != null ? Bukkit.getPlayer(winnerUUID) : null;

        // Stats aktualisieren
        if (winner != null && loser != null) {
            plugin.getPlayerManager().addStat(winner.getUniqueId(), "wins", 1);
            plugin.getPlayerManager().addStat(winner.getUniqueId(), "kills", 1);

            plugin.getPlayerManager().addStat(loser.getUniqueId(), "losses", 1);
            plugin.getPlayerManager().addStat(loser.getUniqueId(), "deaths", 1);
        }

        // Arena resetten wenn Match vorbei
        boolean matchOver = session.getWinsP1() >= session.requiredWins() || session.getWinsP2() >= session.requiredWins();

        if (matchOver) {
            // Coins für Match-Sieg
            if (winner != null) {
                int coinReward = plugin.getConfigManager().getMainConfig().getInt("coins.win-reward", 10);
                plugin.getPlayerManager().addStat(winner.getUniqueId(), "coins", coinReward);
                winner.sendMessage(plugin.getConfigManager().prefixed("duel.coin-reward", "&e+&6{coins} &e{currency} &7(win reward)", java.util.Map.of("coins", String.valueOf(coinReward))));
            }
            Arena arena = plugin.getArenaManager().getArena(session.getArenaName());

            Runnable finish = () -> {
                if (arena != null) arena.setInUse(false);

                cleanupDuel(loserUUID, winnerUUID);

                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    teleportToSpawnSafe(loser);
                    teleportToSpawnSafe(winner);

                    restoreToLobby(loser);
                    restoreToLobby(winner);
                }, 3L);

            };

            Runnable finishSync = () -> Bukkit.getScheduler().runTask(plugin, finish);
            if (arena != null) plugin.getArenaManager().resetArena(arena, finishSync);
            else finishSync.run();


        } else {
            session.setRoundStarting(false);
        }
    }

    private void endDuelByTimeout(Player p1, Player p2) {
        UUID u1 = p1.getUniqueId();
        UUID u2 = p2.getUniqueId();

        DuelSession session = activeDuels.get(u1);
        if (session == null) return;

        int w1 = session.getWinsP1();
        int w2 = session.getWinsP2();

        Player winner = null;
        Player loser = null;

        // Gewinner bestimmen
        if (w1 > w2) {
            winner = p1;
            loser = p2;
        } else if (w2 > w1) {
            winner = p2;
            loser = p1;
        }

        // Nachrichten
        if (winner != null) {
            winner.sendMessage(plugin.getConfigManager().prefixed("duel.won-timeout", "&aYou won the duel &7by timeout!"));
            loser.sendMessage(plugin.getConfigManager().prefixed("duel.lost-timeout", "&cYou lost the duel &7by timeout."));
            String scoreFmt = "§7" + w1 + " §7- §7" + w2 + " §7(by timeout)";
            java.util.Map<String,String> phTO = java.util.Map.of("score", scoreFmt);
            winner.sendTitle(
                plugin.getConfigManager().getMessage("duel.win-title", "§a§lVICTORY", phTO),
                plugin.getConfigManager().getMessage("duel.win-subtitle", scoreFmt, phTO),
                0, 60, 20);
            loser.sendTitle(
                plugin.getConfigManager().getMessage("duel.lose-title", "§c§lDEFEAT", phTO),
                plugin.getConfigManager().getMessage("duel.lose-subtitle", scoreFmt, phTO),
                0, 60, 20);
            winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            loser.playSound(loser.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);

            // Stats
            plugin.getPlayerManager().addStat(winner.getUniqueId(), "wins", 1);
            plugin.getPlayerManager().addStat(winner.getUniqueId(), "kills", 1);

            plugin.getPlayerManager().addStat(loser.getUniqueId(), "losses", 1);
            plugin.getPlayerManager().addStat(loser.getUniqueId(), "deaths", 1);

            // Coins für Match-Sieg
            int coinReward = plugin.getConfigManager().getMainConfig().getInt("coins.win-reward", 10);
            plugin.getPlayerManager().addStat(winner.getUniqueId(), "coins", coinReward);
            winner.sendMessage(plugin.getConfigManager().prefixed("duel.coin-reward", "&e+&6{coins} &e{currency} &7(win reward)", java.util.Map.of("coins", String.valueOf(coinReward))));
        } else {
            // Draw
            p1.sendMessage(plugin.getConfigManager().prefixed("duel.draw", "&eDuel ended in a draw &7(time ran out)."));
            p2.sendMessage(plugin.getConfigManager().prefixed("duel.draw", "&eDuel ended in a draw &7(time ran out)."));
        }

        Arena arena = plugin.getArenaManager().getArena(session.getArenaName());

        Runnable finish = () -> {
            if (arena != null) arena.setInUse(false);


            teleportToSpawnSafe(p1);
            teleportToSpawnSafe(p2);

            restoreToLobby(p1);
            restoreToLobby(p2);

            cleanupDuel(u1, u2);
        };

        // IMPORTANT: immer sync ausführen
        Bukkit.getScheduler().runTask(plugin, finish);
    }


    private void restoreToLobby(Player p) {
        if (p == null || !p.isOnline()) return;

        // Inventar komplett leeren (User-Wunsch: nach jedem Duel Inventar +
        // Armor + Offhand weg, auch wenn der Gegner geleaved hat). Hotbar
        // wird unten via setupPlayerInventory neu gesetzt.
        clearFullInventory(p);

        plugin.getPlayerManager().forceLobbyState(p);
        plugin.getPlayerManager().applyLobbyFly(p);
        plugin.getScoreboardManager().updateScoreboard(p);
        // 2-Tick-Delay: Cross-World-Teleport in den Lobby-Spawn (teleportToSpawnSafe)
        // wirkt erst NACH dem Tick auf player.getWorld(). Wenn wir hier sofort
        // setupPlayerInventory() aufrufen, denkt isInLobbyWorld() noch wir
        // sind in der Arena-Welt und die Hotbar wird NICHT gesetzt — das
        // war die "Hotbar funktioniert nicht mehr nach FFA/Duel"-Ursache.
        final Player pl = p;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!pl.isOnline()) return;
            plugin.getPlayerManager().setupPlayerInventory(pl);
            plugin.getPlayerManager().refreshQueueSlotItem(pl);
        }, 2L);
    }

    /** Leert Hauptinventar, Armor-Slots und Offhand. */
    public static void clearFullInventory(Player p) {
        if (p == null) return;
        var inv = p.getInventory();
        inv.clear();
        inv.setHelmet(null);
        inv.setChestplate(null);
        inv.setLeggings(null);
        inv.setBoots(null);
        inv.setItemInOffHand(null);
        p.updateInventory();
    }

    private void forceRoundState(Player player) {
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0f);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    public void cleanupDuel(UUID player1, UUID player2) {
        // Spectator dieses Matches in die Lobby zurückbringen.
        if (plugin.getSpectateManager() != null && player1 != null && player2 != null) {
            UUID a = player1, b = player2;
            if (a.compareTo(b) > 0) { UUID t = a; a = b; b = t; }
            plugin.getSpectateManager().endMatch("duel:" + a + ":" + b);
        }

        activeDuels.remove(player1);
        if (player2 != null) activeDuels.remove(player2);

        frozenPlayers.remove(player1);
        if (player2 != null) frozenPlayers.remove(player2);

        roundDead.remove(player1);
        if (player2 != null) roundDead.remove(player2);

        savedStates.remove(player1);
        if (player2 != null) savedStates.remove(player2);

        plugin.getPlayerManager().restoreAllVisibility();
    }

    public void cleanupAll() {
        for (DuelSession session : new HashSet<>(activeDuels.values())) {
            cleanupDuel(session.getPlayer1(), session.getPlayer2());
        }
        activeDuels.clear();
        duelRequests.clear();
        frozenPlayers.clear();
        pendingRoundRespawn.clear();
        roundDead.clear();
        autoSelect.clear();
        lastRequestMs.clear();
    }

    public void updateDuelTimers() {
        for (DuelSession session : new HashSet<>(activeDuels.values())) {
            // -1 bedeutet "until-death" Mode: kein Timer, läuft bis einer stirbt.
            if (session.getTimeLeft() < 0) continue;
            // Während einer Runden-Transition (Tod gerade verarbeitet) Timer
            // pausieren, sonst doppelte Timeouts.
            if (session.isRoundStarting()) continue;
            if (session.isMatchEnded()) continue;
            if (session.getTimeLeft() > 0) {
                session.setTimeLeft(session.getTimeLeft() - 1);
            } else {
                Player p1 = Bukkit.getPlayer(session.getPlayer1());
                Player p2 = Bukkit.getPlayer(session.getPlayer2());
                if (p1 != null && p2 != null) {
                    handleRoundTimeout(session, p1, p2);
                }
            }
        }
    }

    /**
     * Round-Timeout: Bei Bo1 (best-of=1) endet das Match. Sonst zählt die
     * aktuelle Runde als unentschieden (kein Win für jemanden), wir
     * advancen in die nächste Runde. Wenn nach allen geplanten Runden
     * (round > bestOf) immer noch niemand requiredWins erreicht hat,
     * gewinnt der mit den meisten Siegen (oder Draw).
     */
    private void handleRoundTimeout(DuelSession session, Player p1, Player p2) {
        // Reentrancy-Schutz wie bei Tod-Verarbeitung.
        if (session.isRoundStarting() || session.isMatchEnded()) return;
        session.setRoundStarting(true);

        int currentRound = session.getRound();
        int bestOf = session.getBestOf();

        // Single-Round Match oder letztmögliche Runde gespielt: Match-Ende.
        if (bestOf <= 1 || currentRound >= bestOf) {
            session.setMatchEnded(true);
            endDuelByTimeout(p1, p2);
            return;
        }

        // Sonst: aktuelle Runde als Timeout-Draw werten und nächste Runde.
        String scoreFormat = "§7" + p1.getName() + " §8(§f" + session.getWinsP1()
                + " §7- §f" + session.getWinsP2() + "§8) §7" + p2.getName();
        p1.sendTitle("§e§lTime up!", "§7Round §f" + currentRound + " §7draw", 0, 40, 10);
        p2.sendTitle("§e§lTime up!", "§7Round §f" + currentRound + " §7draw", 0, 40, 10);
        p1.sendMessage(plugin.getConfigManager().prefixed("duel.round-draw", "&eRound &f#{round} &eended in a draw &7(time up) &8| {score}", java.util.Map.of("round", String.valueOf(currentRound), "score", scoreFormat)));
        p2.sendMessage(plugin.getConfigManager().prefixed("duel.round-draw", "&eRound &f#{round} &eended in a draw &7(time up) &8| {score}", java.util.Map.of("round", String.valueOf(currentRound), "score", scoreFormat)));

        // Spieler sind beide am Leben → direkt zur Arena re-prepare.
        session.setRound(currentRound + 1);
        Arena arena = plugin.getArenaManager().getArena(session.getArenaName());
        if (arena != null && arena.getSpawn1() != null && arena.getSpawn2() != null) {
            pendingRoundRespawn.put(session.getPlayer1(), arena.getSpawn1());
            pendingRoundRespawn.put(session.getPlayer2(), arena.getSpawn2());
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> startNextRound(session), 50L);
    }

    public void addDuelRequest(UUID target, DuelRequest request) {
        UUID senderId = request.getSender();
        long now = System.currentTimeMillis();

        Player sender = Bukkit.getPlayer(senderId);
        Player receiver = Bukkit.getPlayer(target);

        // basic checks
        if (senderId.equals(target)) {
            if (sender != null) sender.sendMessage(plugin.getConfigManager().prefixed("duel.cannot-duel-self", "&cYou can't duel yourself."));
            return;
        }
        if (isInDuel(senderId) || isInDuel(target)) {
            if (sender != null) sender.sendMessage(plugin.getConfigManager().prefixed("duel.someone-in-duel", "&cSomeone is already in a duel."));
            return;
        }
        // Party blockt Duel-Requests in beide Richtungen.
        if (plugin.getPartyManager() != null) {
            if (plugin.getPartyManager().isInParty(senderId)) {
                if (sender != null) sender.sendMessage(plugin.getConfigManager().prefixed("duel.you-in-party", "&cYou are in a party. Leave it to send duel requests."));
                return;
            }
            if (plugin.getPartyManager().isInParty(target)) {
                if (sender != null) sender.sendMessage(plugin.getConfigManager().prefixed("duel.target-in-party", "&cThat player is in a party."));
                return;
            }
        }

        // cooldown (per sender)
        Long last = lastRequestMs.get(senderId);
        if (last != null && (now - last) < REQUEST_COOLDOWN_MS) {
            long leftSec = (REQUEST_COOLDOWN_MS - (now - last) + 999) / 1000;
            if (sender != null) sender.sendMessage(plugin.getConfigManager().prefixed("duel.request-cooldown", "&cWait &f{seconds}s &cbefore sending another request.", java.util.Map.of("seconds", String.valueOf(leftSec))));
            return;
        }
        lastRequestMs.put(senderId, now);

        // Falls target schon eine Request hatte -> alte Arena freigeben
        DuelRequest old = duelRequests.remove(target);
        if (old != null) {
            Arena oldArena = plugin.getArenaManager().getArena(old.getArenaName());
            if (oldArena != null) oldArena.setInUse(false);
        }

        // Arena jetzt auswählen und RESERVIEREN (gefiltert nach erlaubten Kits)
        Arena chosen = plugin.getArenaManager().getRandomAvailableArenaForKit(request.getKitName());
        if (chosen == null) {
            if (sender != null) sender.sendMessage(plugin.getConfigManager().prefixed("queue.no-arena", "&cNo arena available for this kit!"));
            if (receiver != null) receiver.sendMessage(plugin.getConfigManager().prefixed("queue.no-arena", "&cNo arena available for this kit!"));
            return;
        }
        chosen.setInUse(true);

        // Neuen Request bauen, der genau diese Arena enthält
        DuelRequest stored = new DuelRequest(
                request.getSender(),
                request.getTarget(),
                request.getKitName(),
                chosen.getName(),
                request.getBestOf()
        );

        duelRequests.put(target, stored);

        String kitDisplay = plugin.getKitManager().getKitDisplayName(stored.getKitName());

        // Kit/Arena-Zeile: Map nur anzeigen wenn in config aktiviert.
        String kitArenaLine = plugin.getConfigManager().isShowMapInRequest()
                ? plugin.getConfigManager().prefixed("duel.kit-arena", "&7Kit: &r{kit} &7| Arena: &b{arena}", java.util.Map.of("kit", kitDisplay, "arena", chosen.getName()))
                : plugin.getConfigManager().prefixed("duel.kit-only", "&7Kit: &r{kit}", java.util.Map.of("kit", kitDisplay));

        // sender feedback
        if (sender != null && sender.isOnline()) {
            sender.sendMessage("\n" + plugin.getConfigManager().prefixed("duel.request-sent-to", "&aDuel request sent to &e{player}", java.util.Map.of("player", receiver != null ? receiver.getName() : "player")) + "\n" +
                    kitArenaLine + "\n" +
                    plugin.getConfigManager().prefixed("duel.best-of", "&7Best of &f{bestof}", java.util.Map.of("bestof", String.valueOf(stored.getBestOf()))));
        }

        // receiver message
        if (receiver != null && receiver.isOnline()) {
            receiver.sendMessage("\n" + plugin.getConfigManager().prefixed("duel.challenged-you", "&e{player} &7challenged you!", java.util.Map.of("player", sender != null ? sender.getName() : "Someone")) + "\n" +
                    kitArenaLine + "\n" +
                    plugin.getConfigManager().prefixed("duel.best-of", "&7Best of &f{bestof}", java.util.Map.of("bestof", String.valueOf(stored.getBestOf()))));

            receiver.playSound(receiver.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

            Component accept = Component.text("[ACCEPT]", NamedTextColor.GREEN)
                    .hoverEvent(HoverEvent.showText(Component.text("Click to accept", NamedTextColor.GREEN)))
                    .clickEvent(ClickEvent.runCommand("/duel accept"));

            Component deny = Component.text("[DENY]", NamedTextColor.RED)
                    .hoverEvent(HoverEvent.showText(Component.text("Click to deny", NamedTextColor.RED)))
                    .clickEvent(ClickEvent.runCommand("/duel deny"));

            Component msg = Component.text("» ", NamedTextColor.DARK_GRAY)
                    .append(accept)
                    .append(Component.text(" ", NamedTextColor.GRAY))
                    .append(deny)
                    .append(Component.text(" or type ", NamedTextColor.GRAY))
                    .append(Component.text("/duel accept", NamedTextColor.YELLOW));

            receiver.sendMessage(msg);
        }
    }


    public void acceptDuelRequest(Player target) {
        DuelRequest request = duelRequests.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(plugin.getConfigManager().prefixed("duel.no-pending", "&cNo pending duel request."));
            return;
        }
        int timeoutSec = plugin.getConfigManager().getMainConfig()
                .getInt("duel-request-timeout-seconds", 30);
        if (timeoutSec > 0 && request.isExpired(timeoutSec)) {
            Arena a = plugin.getArenaManager().getArena(request.getArenaName());
            if (a != null) a.setInUse(false);
            target.sendMessage(plugin.getConfigManager().prefixed("duel.request-expired-short", "&cDuel request expired."));
            return;
        }
        Player sender = Bukkit.getPlayer(request.getSender());
        if (sender == null || !sender.isOnline()) {
            target.sendMessage(plugin.getConfigManager().prefixed("duel.requester-offline-short", "&cRequester is offline."));
            return;
        }

        if (isInDuel(sender.getUniqueId()) || isInDuel(target.getUniqueId())) {
            target.sendMessage(plugin.getConfigManager().prefixed("duel.someone-in-duel", "&cSomeone is already in a duel."));
            return;
        }

        // start duel
        startDuel(request);
    }

    public void denyDuelRequest(Player target) {
        DuelRequest request = duelRequests.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(plugin.getConfigManager().prefixed("duel.no-pending", "&cNo pending duel request."));
            return;
        }

        Player sender = Bukkit.getPlayer(request.getSender());
        if (sender != null && sender.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().prefixed("duel.request-denied", "&cYour duel request was denied."));
        }
        target.sendMessage(plugin.getConfigManager().prefixed("duel.denied", "&7Request denied."));
        Arena a = plugin.getArenaManager().getArena(request.getArenaName());
        if (a != null) a.setInUse(false);
    }

    public DuelRequest getDuelRequest(UUID target) {
        return duelRequests.get(target);
    }

    public void removeDuelRequest(UUID target) {
        duelRequests.remove(target);
    }

    // Füge diese Methoden zur DuelManager Klasse hinzu:

    public int getActiveDuelCount() {
        // Zählt einzigartige Duels (jedes Duel hat 2 Spieler)
        Set<DuelSession> uniqueSessions = new HashSet<>(activeDuels.values());
        return uniqueSessions.size();
    }

    public Collection<DuelSession> getAllSessions() {
        // Gibt alle einzigartigen Sessions zurück
        return new HashSet<>(activeDuels.values());
    }

    public void handlePlayerDisconnect(UUID playerUUID) {
        DuelSession session = activeDuels.get(playerUUID);
        if (session == null) return;

        UUID opponentUUID = session.getOpponent(playerUUID);
        Player opponent = Bukkit.getPlayer(opponentUUID);

        // Opponent benachrichtigen
        if (opponent != null && opponent.isOnline()) {
            opponent.sendMessage(plugin.getConfigManager().prefixed("duel.opponent-disconnected", "&cYour opponent disconnected! You win!"));

            // Stats für Gegner
            plugin.getPlayerManager().addStat(opponentUUID, "wins", 1);
            plugin.getPlayerManager().addStat(opponentUUID, "kills", 1);

            // Coins für Match-Sieg (Disconnect-Forfeit)
            int coinReward = plugin.getConfigManager().getMainConfig().getInt("coins.win-reward", 10);
            plugin.getPlayerManager().addStat(opponentUUID, "coins", coinReward);
            opponent.sendMessage(plugin.getConfigManager().prefixed("duel.coin-reward", "&e+&6{coins} &e{currency} &7(win reward)", java.util.Map.of("coins", String.valueOf(coinReward))));

            // Stats für disconnected Spieler
            plugin.getPlayerManager().addStat(playerUUID, "losses", 1);
            plugin.getPlayerManager().addStat(playerUUID, "deaths", 1);
        }

        // Arena zurücksetzen
        Arena arena = plugin.getArenaManager().getArena(session.getArenaName());
        if (arena != null) {
            arena.setInUse(false);
            plugin.getArenaManager().resetArena(arena, null);
        }

        // Duel aufräumen
        cleanupDuel(playerUUID, opponentUUID);

        // Gegner zum Spawn teleportieren
        if (opponent != null && opponent.isOnline()) {
            plugin.getPlayerManager().teleportToSpawn(opponent);
        }
    }

    public void handleForfeit(Player player) {
        UUID playerUUID = player.getUniqueId();
        DuelSession session = activeDuels.get(playerUUID);

        if (session == null) {
            player.sendMessage(plugin.getConfigManager().prefixed("duel.not-in-duel", "&cYou are not in a duel!"));
            return;
        }

        UUID opponentUUID = session.getOpponent(playerUUID);
        Player opponent = Bukkit.getPlayer(opponentUUID);

        // Nachrichten senden
        player.sendMessage(plugin.getConfigManager().prefixed("duel.forfeited", "&cYou forfeited the duel! &7This counts as a loss."));

        if (opponent != null && opponent.isOnline()) {
            opponent.sendMessage(plugin.getConfigManager().prefixed("duel.opponent-forfeited", "&a{player} &7forfeited the duel! &aYou win!", java.util.Map.of("player", player.getName())));
        }

        // Stats aktualisieren
        plugin.getPlayerManager().addStat(playerUUID, "losses", 1);
        plugin.getPlayerManager().addStat(playerUUID, "deaths", 1);

        if (opponent != null) {
            plugin.getPlayerManager().addStat(opponentUUID, "wins", 1);
            plugin.getPlayerManager().addStat(opponentUUID, "kills", 1);
            int coinReward = plugin.getConfigManager().getMainConfig().getInt("coins.win-reward", 10);
            plugin.getPlayerManager().addStat(opponentUUID, "coins", coinReward);
            if (opponent.isOnline()) opponent.sendMessage(plugin.getConfigManager().prefixed("duel.coin-reward", "&e+&6{coins} &e{currency} &7(win reward)", java.util.Map.of("coins", String.valueOf(coinReward))));
        }

        // Arena zurücksetzen
        Arena arena = plugin.getArenaManager().getArena(session.getArenaName());
        if (arena != null) {
            arena.setInUse(false);
            plugin.getArenaManager().resetArena(arena, null);
        }

        // Duel aufräumen
        cleanupDuel(playerUUID, opponentUUID);

        // Beide Spieler zum Spawn teleportieren
        plugin.getPlayerManager().teleportToSpawn(player);
        if (opponent != null && opponent.isOnline()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                plugin.getPlayerManager().teleportToSpawn(opponent);
            }, 2L);
        }
    }

    public boolean isInDuel(UUID uuid) {
        return activeDuels.containsKey(uuid);
    }

    public DuelSession getDuelSession(UUID uuid) {
        return activeDuels.get(uuid);
    }

    /** Liefert die Arena in der sich der Spieler gerade duelliert (oder null). */
    public Arena getArenaOf(UUID uuid) {
        DuelSession s = activeDuels.get(uuid);
        if (s == null) return null;
        return plugin.getArenaManager().getArena(s.getArenaName());
    }

    public boolean isFrozen(UUID uuid) {
        return frozenPlayers.contains(uuid);
    }

    public Location getPendingRespawn(UUID uuid) {
        return pendingRoundRespawn.get(uuid);
    }

    public void removePendingRespawn(UUID uuid) {
        pendingRoundRespawn.remove(uuid);
    }

    public boolean isRoundDead(UUID uuid) {
        return roundDead.contains(uuid);
    }

    public void handleAutoSelect(Player picker, Player target, String kitName, int bestOf) {
        // Auto-Select-Logik
    }

    // Innere Klassen für AutoSelect
    private static class PairKey {
        final UUID a, b;

        PairKey(UUID x, UUID y) {
            if (x.compareTo(y) <= 0) {
                this.a = x;
                this.b = y;
            } else {
                this.a = y;
                this.b = x;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PairKey)) return false;
            PairKey k = (PairKey) o;
            return a.equals(k.a) && b.equals(k.b);
        }

        @Override
        public int hashCode() {
            return Objects.hash(a, b);
        }
    }

    private static class AutoSelect {
        final UUID firstPicker;
        final String kitName;
        final int bestOf;
        final long timestamp;

        AutoSelect(UUID firstPicker, String kitName, int bestOf) {
            this.firstPicker = firstPicker;
            this.kitName = kitName;
            this.bestOf = bestOf;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > AUTOSELECT_TIMEOUT_MS;
        }
    }

    // ---------- Party Duel Starters ----------

    /**
     * Startet eine Reihe von 1v1-Duells aus paarweise gepickten Spielern.
     * Jedes Paar bekommt eine eigene Arena (sofern verfügbar). Spieler ohne
     * Partner sitzen aus.
     */
    public int startPartyPairs(List<Player> pairsFlat, String kitName, int bestOf) {
        int started = 0;
        for (int i = 0; i + 1 < pairsFlat.size(); i += 2) {
            Player a = pairsFlat.get(i);
            Player b = pairsFlat.get(i + 1);
            if (a == null || b == null || !a.isOnline() || !b.isOnline()) continue;
            if (isInDuel(a.getUniqueId()) || isInDuel(b.getUniqueId())) continue;

            Arena arena = plugin.getArenaManager().getRandomAvailableArenaForKit(kitName);
            if (arena == null) {
                a.sendMessage(plugin.getConfigManager().prefixed("duel.no-arena-skip", "&cNo available arena for this kit; skipping pair."));
                b.sendMessage(plugin.getConfigManager().prefixed("duel.no-arena-skip", "&cNo available arena for this kit; skipping pair."));
                continue;
            }
            arena.setInUse(true);

            DuelRequest req = new DuelRequest(
                    a.getUniqueId(), b.getUniqueId(),
                    kitName, arena.getName(),
                    Math.max(1, bestOf)
            );
            startDuel(req);
            started++;
        }
        return started;
    }

    /** 1v1 zwischen zwei Party-Mitgliedern starten. */
    public boolean startPartyDuelOne(Player a, Player b, String kitName, int bestOf) {
        if (a == null || b == null || !a.isOnline() || !b.isOnline()) return false;
        if (isInDuel(a.getUniqueId()) || isInDuel(b.getUniqueId())) {
            a.sendMessage(plugin.getConfigManager().prefixed("duel.one-already-in-duel", "&cOne of you is already in a duel."));
            return false;
        }
        Arena arena = plugin.getArenaManager().getRandomAvailableArenaForKit(kitName);
        if (arena == null) {
            a.sendMessage(plugin.getConfigManager().prefixed("duel.no-arena-available", "&cNo available arena for this kit!"));
            b.sendMessage(plugin.getConfigManager().prefixed("duel.no-arena-available", "&cNo available arena for this kit!"));
            return false;
        }
        arena.setInUse(true);
        DuelRequest req = new DuelRequest(
                a.getUniqueId(), b.getUniqueId(),
                kitName, arena.getName(),
                Math.max(1, bestOf)
        );
        startDuel(req);
        return true;
    }

    /**
     * FFA: Mitglieder zufällig in 1v1-Paare einteilen. Bei ungerader Anzahl
     * sitzt ein Spieler aus.
     */
    public int startPartyFFA(List<Player> members, String kitName, int bestOf) {
        List<Player> shuffled = new ArrayList<>(members);
        Collections.shuffle(shuffled);
        if (shuffled.size() < 2) return 0;
        return startPartyPairs(shuffled, kitName, bestOf);
    }

    /**
     * Team vs Team: alle Team-1-Mitglieder werden in Reihenfolge gegen alle
     * Team-2-Mitglieder gepairt. Überzählige sitzen aus.
     */
    public int startPartyTeams(List<Player> team1, List<Player> team2, String kitName, int bestOf) {
        int n = Math.min(team1.size(), team2.size());
        if (n < 1) return 0;
        List<Player> flat = new ArrayList<>(n * 2);
        for (int i = 0; i < n; i++) {
            flat.add(team1.get(i));
            flat.add(team2.get(i));
        }
        return startPartyPairs(flat, kitName, bestOf);
    }
}