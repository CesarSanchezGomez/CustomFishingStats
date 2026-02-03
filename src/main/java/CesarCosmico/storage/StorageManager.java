package CesarCosmico.storage;

import CesarCosmico.CustomFishingStats;
import CesarCosmico.storage.data.PlayerData;
import CesarCosmico.storage.method.file.YAMLProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Gestor de almacenamiento de datos de jugadores.
 * Responsabilidad única: Gestionar la persistencia y caché de datos de jugadores.
 *
 * Aplica:
 * - Single Responsibility: Solo maneja persistencia y caché
 * - Open/Closed: Extensible mediante providers
 * - Dependency Inversion: Depende de abstracciones (YAMLProvider)
 */
public class StorageManager implements Listener {
    private final CustomFishingStats plugin;
    private final YAMLProvider yamlStorage;
    private final ConcurrentHashMap<UUID, PlayerData> onlinePlayers;
    private final ConcurrentHashMap<UUID, PlayerData> offlineCache;
    private boolean autoSaveLog;

    public StorageManager(CustomFishingStats plugin) {
        this.plugin = plugin;
        this.yamlStorage = new YAMLProvider(plugin);
        this.onlinePlayers = new ConcurrentHashMap<>();
        this.offlineCache = new ConcurrentHashMap<>();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        loadConfig();
    }

    private void loadConfig() {
        this.autoSaveLog = plugin.getConfigManager().getConfig().getBoolean("storage.auto-save.log", true);
    }

    public void start() {
        // No iniciamos auto-save aquí, se maneja centralmente en el plugin
    }

    /**
     * Auto-guardado: persiste SOLO jugadores online
     * OPTIMIZED: No guarda jugadores offline del caché temporal
     * También limpia el caché offline si crece demasiado
     */
    private void performAutoSave() {
        if (!autoSaveLog) {
            pruneOfflineCache(100);
            return;
        }

        if (onlinePlayers.isEmpty()) {
            if (autoSaveLog) {
                plugin.getLogger().info("No online players to save");
            }
            pruneOfflineCache(100);
            return;
        }

        List<CompletableFuture<Boolean>> saveFutures = new ArrayList<>();

        for (PlayerData playerData : onlinePlayers.values()) {
            saveFutures.add(yamlStorage.savePlayerData(playerData));
        }

        int totalPlayers = saveFutures.size();

        CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0])).thenRun(() -> {
            if (autoSaveLog) {
                plugin.getLogger().info("Saved " + totalPlayers + " online players");
            }
            pruneOfflineCache(100);
        }).exceptionally(throwable -> {
            plugin.getLogger().severe("Auto-save error: " + throwable.getMessage());
            return null;
        });
    }

    /**
     * Ejecuta auto-save inmediatamente (llamado desde el plugin principal)
     * EXPOSED: Para sincronizar con global stats save
     */
    public void performAutoSaveNow() {
        performAutoSave();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerData cachedOfflineData = offlineCache.remove(uuid);

        if (cachedOfflineData != null) {
            cachedOfflineData.setName(player.getName());
            onlinePlayers.put(uuid, cachedOfflineData);
        } else {
            yamlStorage.loadPlayerData(uuid).thenAccept(playerData -> {
                if (playerData != null) {
                    playerData.setName(player.getName());
                    onlinePlayers.put(uuid, playerData);
                }
            });
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        PlayerData playerData = onlinePlayers.remove(uuid);

        if (playerData != null) {
            yamlStorage.savePlayerData(playerData).exceptionally(throwable -> {
                plugin.getLogger().severe("Error saving player data on quit: " + throwable.getMessage());
                return false;
            });
        }
    }

    /**
     * Carga datos del jugador desde caché o disco
     */
    public CompletableFuture<PlayerData> loadPlayerData(UUID uuid) {
        PlayerData online = onlinePlayers.get(uuid);
        if (online != null) {
            return CompletableFuture.completedFuture(online);
        }

        PlayerData offline = offlineCache.get(uuid);
        if (offline != null) {
            return CompletableFuture.completedFuture(offline);
        }

        return yamlStorage.loadPlayerData(uuid).thenApply(playerData -> {
            if (playerData != null) {
                offlineCache.put(uuid, playerData);
            }
            return playerData;
        });
    }

    /**
     * Modifica datos del jugador y asegura visibilidad inmediata en caché
     * CRITICAL: Los cambios son visibles instantáneamente para placeholders y rankings
     *
     * Estrategia de guardado:
     * - Jugadores ONLINE: Solo modifica caché, se guarda en auto-save
     * - Jugadores OFFLINE: Modifica caché Y guarda inmediatamente (comandos admin)
     */
    public <T> CompletableFuture<T> modifyPlayerData(UUID uuid, Function<PlayerData, T> modifier) {
        PlayerData online = onlinePlayers.get(uuid);
        if (online != null) {
            // Jugador online: solo modificar caché, auto-save lo guardará
            return modifyAndNotify(uuid, online, modifier, false);
        }

        PlayerData offline = offlineCache.get(uuid);
        if (offline != null) {
            // Jugador offline en caché: modificar y guardar inmediatamente
            return modifyAndNotify(uuid, offline, modifier, true);
        }

        // Cargar desde disco, modificar y guardar
        return yamlStorage.loadPlayerData(uuid).thenCompose(playerData -> {
            if (playerData == null) {
                String playerName = Bukkit.getOfflinePlayer(uuid).getName();
                playerData = new PlayerData(uuid, playerName != null ? playerName : "Unknown");
            }

            offlineCache.put(uuid, playerData);
            return modifyAndNotify(uuid, playerData, modifier, true);
        });
    }

    /**
     * Modifica datos y notifica al sistema de rankings
     * @param saveImmediately true para jugadores offline (comandos), false para online (gameplay)
     */
    private <T> CompletableFuture<T> modifyAndNotify(UUID uuid, PlayerData playerData,
                                                     Function<PlayerData, T> modifier,
                                                     boolean saveImmediately) {
        try {
            T result = modifier.apply(playerData);
            plugin.notifyPlayerDataChanged(uuid);

            if (saveImmediately) {
                return yamlStorage.savePlayerData(playerData).thenApply(success -> result);
            }

            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            plugin.getLogger().severe("Error modifying player data for " + uuid + ": " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Modifica datos de jugador online sin persistir inmediatamente
     * Los datos se guardarán en el próximo auto-save
     */
    public void modifyOnlinePlayerDataLazy(UUID uuid, Function<PlayerData, Void> modifier) {
        PlayerData playerData = onlinePlayers.get(uuid);
        if (playerData != null) {
            modifier.apply(playerData);
            plugin.notifyPlayerDataChanged(uuid);
        }
    }

    /**
     * Obtiene snapshot de datos de jugador online (para rankings)
     */
    public PlayerData getOnlinePlayerSnapshot(UUID uuid) {
        return onlinePlayers.get(uuid);
    }

    /**
     * Obtiene snapshot de datos de jugador offline en caché (para rankings)
     */
    public PlayerData getOfflineCachedSnapshot(UUID uuid) {
        return offlineCache.get(uuid);
    }

    /**
     * Verifica si un jugador está en caché (online u offline)
     */
    public boolean isPlayerCached(UUID uuid) {
        return onlinePlayers.containsKey(uuid) || offlineCache.containsKey(uuid);
    }

    /**
     * Limpia caché de jugadores offline
     * Útil para liberar memoria después de recalcular rankings
     * OPTIMIZED: Mantiene solo los últimos N jugadores modificados
     */
    public void clearOfflineCache() {
        offlineCache.clear();
    }

    /**
     * Limpia jugadores offline del caché que no han sido accedidos recientemente
     * Mantiene memoria bajo control
     */
    public void pruneOfflineCache(int maxSize) {
        if (offlineCache.size() <= maxSize) return;

        // Si excede el tamaño, limpiar todo el caché offline
        // Los datos importantes están guardados en disco
        offlineCache.clear();
    }

    /**
     * Limpia caché de un jugador offline específico
     */
    public void clearOfflinePlayerCache(UUID uuid) {
        if (!onlinePlayers.containsKey(uuid)) {
            offlineCache.remove(uuid);
        }
    }

    /**
     * Reload sin perder datos en memoria
     * CRITICAL: Preserva caché de jugadores online durante reload
     */
    public void reload() {
        loadConfig();
        // NO limpiamos onlinePlayers ni offlineCache
        // Los datos en memoria se mantienen intactos
    }

    public void disable() {
        saveAllDataSync();
        onlinePlayers.clear();
        offlineCache.clear();
    }

    public void saveAllDataSync() {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (PlayerData playerData : onlinePlayers.values()) {
            futures.add(yamlStorage.savePlayerData(playerData));
        }

        for (PlayerData playerData : offlineCache.values()) {
            futures.add(yamlStorage.savePlayerData(playerData));
        }

        if (futures.isEmpty()) return;

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        plugin.getLogger().info("Saved " + futures.size() + " players");
    }
}