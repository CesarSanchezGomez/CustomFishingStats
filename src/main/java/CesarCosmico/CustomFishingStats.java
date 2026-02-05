package CesarCosmico;

import CesarCosmico.actions.FishingStatAction;
import CesarCosmico.commands.CommandManager;
import CesarCosmico.config.ConfigManager;
import CesarCosmico.config.DisplayNamesManager;
import CesarCosmico.config.MessagesManager;
import CesarCosmico.placeholderapi.FishingStatsExpansion;
import CesarCosmico.services.GlobalStatsService;
import CesarCosmico.services.RankingService;
import CesarCosmico.storage.StorageManager;
import CesarCosmico.actions.FishingStatContext;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.mechanic.action.ActionManager;
import net.momirealms.customfishing.api.mechanic.statistic.StatisticsManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class CustomFishingStats extends JavaPlugin {

    private BukkitCustomFishingPlugin customFishingPlugin;
    private ActionManager<Player> actionManager;
    private StatisticsManager customFishingStatsManager;

    private ConfigManager configManager;
    private MessagesManager messagesManager;
    private StorageManager storageManager;
    private GlobalStatsService globalStatsService;
    private DisplayNamesManager displayNamesManager;
    private RankingService rankingService;

    private FishingStatAction fishingStatAction;
    private FishingStatsExpansion papiExpansion;

    private final Map<String, Set<String>> customFishingCategories = new HashMap<>();

    private BukkitTask autoSaveTask;
    private BukkitTask rankingCacheTask;

    @Override
    public void onEnable() {
        if (!initializeCustomFishing()) {
            return;
        }

        loadCustomFishingCategories();
        initializeServices();
        loadData();
        registerFeatures();
        scheduleAutoSave();
        scheduleRankingCache();
    }

    @Override
    public void onDisable() {
        unregisterFeatures();
        cancelTasks();
        saveData();

        if (rankingService != null) {
            rankingService.shutdown();
        }
    }

    private boolean initializeCustomFishing() {
        this.customFishingPlugin = BukkitCustomFishingPlugin.getInstance();
        if (customFishingPlugin == null) {
            getLogger().severe("CustomFishing is not installed! Disabling...");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        this.actionManager = customFishingPlugin.getActionManager();
        this.customFishingStatsManager = customFishingPlugin.getStatisticsManager();
        return true;
    }

    private void loadCustomFishingCategories() {
        customFishingCategories.clear();
        File contentsFolder = new File(customFishingPlugin.getDataFolder(), "contents");
        if (!contentsFolder.exists()) {
            return;
        }

        File categoryFolder = new File(contentsFolder, "category");
        if (categoryFolder.exists() && categoryFolder.isDirectory()) {
            loadCategoriesFromFolder(categoryFolder, "category");
        }
    }

    private void loadCategoriesFromFolder(File folder, String type) {
        Set<String> categories = new HashSet<>();
        Deque<File> fileQueue = new ArrayDeque<>();
        fileQueue.push(folder);

        while (!fileQueue.isEmpty()) {
            File current = fileQueue.pop();
            File[] files = current.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (file.isDirectory()) {
                    fileQueue.push(file);
                } else if (file.isFile() && file.getName().endsWith(".yml")) {
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                    categories.addAll(config.getKeys(false));
                }
            }
        }

        if (!categories.isEmpty()) {
            customFishingCategories.put(type, categories);
        }
    }

    private void initializeServices() {
        this.configManager = new ConfigManager(this);
        this.messagesManager = new MessagesManager(this);
        this.storageManager = new StorageManager(this);
        this.displayNamesManager = new DisplayNamesManager(this);

        boolean enableLog = configManager.getConfig().getBoolean("storage.auto-save.log", true);
        this.globalStatsService = new GlobalStatsService(getLogger(), getDataFolder(), enableLog);
        this.rankingService = new RankingService(storageManager, getDataFolder(), customFishingCategories);

        storageManager.start();
    }

    private void loadData() {
        globalStatsService.load();
    }

    private void registerFeatures() {
        registerTrackingAction();
        registerCommands();
        registerPlaceholderAPI();
    }

    private void registerTrackingAction() {
        this.fishingStatAction = new FishingStatAction(this);
        fishingStatAction.register();
    }

    private void registerCommands() {
        LifecycleEventManager<Plugin> manager = getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            CommandManager commandManager = new CommandManager(this);
            event.registrar().register(
                    commandManager.createCommand(),
                    "View fishing statistics (categories, competitions, events)"
            );
        });
    }

    private void registerPlaceholderAPI() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.papiExpansion = new FishingStatsExpansion(this);
            papiExpansion.register();
        }
    }

    private void scheduleAutoSave() {
        if (autoSaveTask != null && !autoSaveTask.isCancelled()) {
            autoSaveTask.cancel();
        }

        long autoSaveInterval = configManager.getConfig().getLong("storage.auto-save.interval", 300) * 20L;
        this.autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                this::performUnifiedAutoSave,
                autoSaveInterval,
                autoSaveInterval);
    }

    private void performUnifiedAutoSave() {
        boolean enableLog = configManager.getConfig().getBoolean("storage.auto-save.log", true);

        if (enableLog) {
            getLogger().info("Starting auto-save...");
        }

        storageManager.performAutoSaveNow();
        globalStatsService.autoSave();

        if (enableLog) {
            getLogger().info("Auto-save completed");
        }
    }

    private void scheduleRankingCache() {
        if (rankingCacheTask != null && !rankingCacheTask.isCancelled()) {
            rankingCacheTask.cancel();
        }

        long cacheInterval = 5 * 60 * 20L;
        this.rankingCacheTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> rankingService.recalculateAll(),
                cacheInterval,
                cacheInterval);
    }

    private void cancelTasks() {
        if (autoSaveTask != null && !autoSaveTask.isCancelled()) {
            autoSaveTask.cancel();
        }
        if (rankingCacheTask != null && !rankingCacheTask.isCancelled()) {
            rankingCacheTask.cancel();
        }
    }

    private void unregisterFeatures() {
        if (papiExpansion != null) {
            papiExpansion.unregister();
        }
    }

    private void saveData() {
        if (storageManager != null) {
            storageManager.disable();
        }
        if (globalStatsService != null) {
            globalStatsService.save();
        }
    }

    public void trackStats(Player player, FishingStatContext context) {
        if (player != null) {
            storageManager.modifyOnlinePlayerDataLazy(player.getUniqueId(),
                    playerData -> {
                        playerData.addStats(context);
                        return null;
                    });
        }

        globalStatsService.increment(context);
        rankingService.invalidateCategoryCache(context.getType(), context.getCategory());
    }

    public CompletableFuture<Void> addStatsTransactional(UUID uuid, FishingStatContext context) {
        if (uuid == null) {
            globalStatsService.increment(context);
            rankingService.invalidateCategoryCache(context.getType(), context.getCategory());
            return CompletableFuture.completedFuture(null);
        }

        return storageManager.modifyPlayerData(uuid, playerData -> {
            playerData.addStats(context);
            return null;
        }).thenRun(() -> {
            globalStatsService.increment(context);
            rankingService.invalidateCategoryCache(context.getType(), context.getCategory());
        });
    }

    public CompletableFuture<Integer> removeStatsTransactional(UUID uuid, FishingStatContext context) {
        if (uuid == null) {
            String type = context.getType();
            String category = context.getCategory();
            int requested = context.getAmount();

            int current = context.hasItem()
                    ? globalStatsService.getCategoryItems(type, category)
                    .getOrDefault(context.getItem(), 0)
                    : globalStatsService.getCategoryTotal(type, category);

            int actuallyRemoved = Math.min(current, requested);

            if (actuallyRemoved > 0) {
                FishingStatContext adjustedContext = FishingStatContext.builder()
                        .type(type)
                        .category(category)
                        .amount(actuallyRemoved)
                        .item(context.hasItem() ? context.getItem() : null)
                        .build();

                globalStatsService.decrement(adjustedContext);
                rankingService.invalidateCategoryCache(type, category);
            }

            return CompletableFuture.completedFuture(actuallyRemoved);
        }

        return storageManager.modifyPlayerData(uuid, playerData -> {
            String type = context.getType();
            String category = context.getCategory();
            int requested = context.getAmount();

            int current = context.hasItem()
                    ? playerData.getItemAmount(type, category, context.getItem())
                    : playerData.getCategoryTotal(type, category);

            int actuallyRemoved = Math.min(current, requested);

            if (actuallyRemoved > 0) {
                FishingStatContext adjustedContext = FishingStatContext.builder()
                        .type(type)
                        .category(category)
                        .amount(actuallyRemoved)
                        .item(context.hasItem() ? context.getItem() : null)
                        .build();

                playerData.removeStats(adjustedContext);
            }

            return actuallyRemoved;
        }).thenApply(actuallyRemoved -> {
            if (actuallyRemoved > 0) {
                FishingStatContext adjustedContext = FishingStatContext.builder()
                        .type(context.getType())
                        .category(context.getCategory())
                        .amount(actuallyRemoved)
                        .item(context.hasItem() ? context.getItem() : null)
                        .build();

                globalStatsService.decrement(adjustedContext);
                rankingService.invalidateCategoryCache(context.getType(), context.getCategory());
            }
            return actuallyRemoved;
        });
    }

    public void notifyPlayerDataChanged(UUID uuid) {
        rankingService.invalidatePlayerCache(uuid);
    }

    public int getCategoryTotal(String type, String category) {
        return globalStatsService.getCategoryTotal(type, category);
    }

    public Map<String, Integer> getCategoryItems(String type, String category) {
        return globalStatsService.getCategoryItems(type, category);
    }

    public int getGlobalTotal(String type) {
        return globalStatsService.getTotalByType(type);
    }

    public boolean isGlobalOnlyCategory(String type, String category) {
        return globalStatsService.isGlobalOnlyCategory(type, category);
    }

    public List<String> getAllTypes() {
        Set<String> allTypes = new LinkedHashSet<>();
        Set<String> yourTypes = globalStatsService.getAllTypes();
        if (yourTypes != null) {
            allTypes.addAll(yourTypes);
        }
        allTypes.addAll(customFishingCategories.keySet());
        return new ArrayList<>(allTypes);
    }

    public List<String> getCategoriesByType(String type) {
        Set<String> allCategories = new LinkedHashSet<>();
        Set<String> yourCategories = globalStatsService.getCategoriesByType(type);
        if (yourCategories != null) {
            allCategories.addAll(yourCategories);
        }
        Set<String> cfCategories = customFishingCategories.get(type);
        if (cfCategories != null) {
            allCategories.addAll(cfCategories);
        }
        return new ArrayList<>(allCategories);
    }

    public List<Map.Entry<String, Integer>> getTopPlayers(String type, String category, int limit) {
        return rankingService.getTopPlayers(type, category, limit);
    }

    public int getPlayerRank(UUID uuid, String type, String category) {
        return rankingService.getPlayerRank(uuid, type, category);
    }

    public RankingService getRankingService() {
        return rankingService;
    }

    public MessagesManager getMessages() {
        return messagesManager;
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DisplayNamesManager getDisplayNamesManager() {
        return displayNamesManager;
    }

    public ActionManager<Player> getActionManager() {
        return actionManager;
    }

    public void reloadPlugin() {
        if (storageManager != null) {
            storageManager.saveAllDataSync();
        }
        if (globalStatsService != null) {
            globalStatsService.save();
        }

        configManager.reload();
        messagesManager.reload();
        storageManager.reload();
        displayNamesManager.reload();
        loadCustomFishingCategories();
        globalStatsService.load();

        scheduleAutoSave();
        scheduleRankingCache();

        rankingService.clearCache();
        rankingService.recalculateAll();
    }
}