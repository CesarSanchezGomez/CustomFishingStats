package CesarCosmico.services;

import CesarCosmico.actions.FishingStatContext;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

public class GlobalStatsService {

    private final Logger logger;
    private final File storageFolder;
    private final boolean enableAutoSaveLog;

    private final Map<String, Map<String, Map<String, Integer>>> globalStats = new ConcurrentHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean statsModified = false;

    public GlobalStatsService(Logger logger, File dataFolder, boolean enableAutoSaveLog) {
        this.logger = logger;
        this.storageFolder = new File(dataFolder, "storage");
        this.enableAutoSaveLog = enableAutoSaveLog;

        if (!storageFolder.exists()) {
            storageFolder.mkdirs();
        }
    }

    public void increment(FishingStatContext context) {
        String type = context.getType();
        String category = context.getCategory();
        int amount = context.getAmount();

        lock.writeLock().lock();
        try {
            Map<String, Map<String, Integer>> typeData = globalStats.computeIfAbsent(type,
                    k -> new ConcurrentHashMap<>());
            Map<String, Integer> categoryData = typeData.computeIfAbsent(category,
                    k -> new ConcurrentHashMap<>());

            categoryData.merge("total", amount, Integer::sum);

            if (context.hasItem()) {
                categoryData.merge(context.getItem(), amount, Integer::sum);
            }

            statsModified = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void decrement(FishingStatContext context) {
        String type = context.getType();
        String category = context.getCategory();
        int amount = context.getAmount();

        lock.writeLock().lock();
        try {
            Map<String, Map<String, Integer>> typeData = globalStats.get(type);
            if (typeData == null) return;

            Map<String, Integer> categoryData = typeData.get(category);
            if (categoryData == null) return;

            int currentTotal = categoryData.getOrDefault("total", 0);
            int newTotal = Math.max(0, currentTotal - amount);

            if (newTotal > 0) {
                categoryData.put("total", newTotal);
            } else {
                categoryData.remove("total");
            }

            if (context.hasItem()) {
                String itemId = context.getItem();
                int currentItem = categoryData.getOrDefault(itemId, 0);
                int newItem = Math.max(0, currentItem - amount);

                if (newItem > 0) {
                    categoryData.put(itemId, newItem);
                } else {
                    categoryData.remove(itemId);
                }
            }

            cleanupEmptyStats(type, category, typeData, categoryData);
            statsModified = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void cleanupEmptyStats(String type, String category,
                                   Map<String, Map<String, Integer>> typeData,
                                   Map<String, Integer> categoryData) {
        if (categoryData.isEmpty() || (categoryData.size() == 1 &&
                categoryData.containsKey("total") && categoryData.get("total") == 0)) {
            typeData.remove(category);
        }
        if (typeData.isEmpty()) {
            globalStats.remove(type);
        }
    }

    public int getCategoryTotal(String type, String category) {
        lock.readLock().lock();
        try {
            Map<String, Map<String, Integer>> typeData = globalStats.get(type);
            if (typeData == null) return 0;
            Map<String, Integer> categoryData = typeData.get(category);
            if (categoryData == null) return 0;
            return categoryData.getOrDefault("total", 0);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Map<String, Integer> getCategoryItems(String type, String category) {
        lock.readLock().lock();
        try {
            Map<String, Map<String, Integer>> typeData = globalStats.get(type);
            if (typeData == null) return new HashMap<>();
            Map<String, Integer> categoryData = typeData.get(category);
            if (categoryData == null) return new HashMap<>();
            Map<String, Integer> items = new HashMap<>(categoryData);
            items.remove("total");
            return items;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Set<String> getCategoriesByType(String type) {
        lock.readLock().lock();
        try {
            Map<String, Map<String, Integer>> typeData = globalStats.get(type);
            if (typeData == null) return new HashSet<>();
            return new HashSet<>(typeData.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Set<String> getAllTypes() {
        lock.readLock().lock();
        try {
            return new HashSet<>(globalStats.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isGlobalOnlyCategory(String type, String category) {
        if (!type.equals("competition")) {
            return false;
        }

        return category.endsWith("_count") ||
                category.equals("count") ||
                category.startsWith("participation");
    }

    public int getTotalByType(String type) {
        lock.readLock().lock();
        try {
            Map<String, Map<String, Integer>> typeData = globalStats.get(type);
            if (typeData == null) return 0;
            return typeData.values().stream()
                    .mapToInt(categoryData -> categoryData.getOrDefault("total", 0))
                    .sum();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void load() {
        File globalStatsFile = new File(storageFolder, "global_stats.yml");
        if (!globalStatsFile.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(globalStatsFile);
        ConfigurationSection contextsSection = config.getConfigurationSection("contexts");

        lock.writeLock().lock();
        try {
            globalStats.clear();

            if (contextsSection == null) {
                loadLegacyFormat(config);
                return;
            }

            loadHierarchicalFormat(contextsSection);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void loadHierarchicalFormat(ConfigurationSection contextsSection) {
        for (String type : contextsSection.getKeys(false)) {
            ConfigurationSection typeSection = contextsSection.getConfigurationSection(type);
            if (typeSection == null) continue;

            Map<String, Map<String, Integer>> typeData = new ConcurrentHashMap<>();

            for (String category : typeSection.getKeys(false)) {
                ConfigurationSection categorySection = typeSection.getConfigurationSection(category);
                if (categorySection == null) continue;

                Map<String, Integer> categoryData = new ConcurrentHashMap<>();
                categoryData.put("total", categorySection.getInt("total", 0));

                ConfigurationSection itemsSection = categorySection.getConfigurationSection("items");
                if (itemsSection != null) {
                    for (String itemId : itemsSection.getKeys(false)) {
                        categoryData.put(itemId, itemsSection.getInt(itemId, 0));
                    }
                }

                typeData.put(category, categoryData);
            }

            globalStats.put(type, typeData);
        }
    }

    private void loadLegacyFormat(YamlConfiguration config) {
        ConfigurationSection oldContexts = config.getConfigurationSection("contexts");
        if (oldContexts == null) return;

        for (String contextKey : oldContexts.getKeys(false)) {
            String[] parts = contextKey.split(":", 2);
            if (parts.length != 2) continue;

            String type = parts[0];
            String category = parts[1];

            ConfigurationSection contextData = oldContexts.getConfigurationSection(contextKey);
            if (contextData == null) continue;

            Map<String, Integer> data = new ConcurrentHashMap<>();
            data.put("total", contextData.getInt("total", 0));

            ConfigurationSection itemsSection = contextData.getConfigurationSection("items");
            if (itemsSection != null) {
                for (String itemId : itemsSection.getKeys(false)) {
                    data.put(itemId, itemsSection.getInt(itemId, 0));
                }
            }

            globalStats.computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                    .put(category, data);
        }

        statsModified = true;
    }

    public void save() {
        File globalStatsFile = new File(storageFolder, "global_stats.yml");
        YamlConfiguration config = new YamlConfiguration();

        lock.readLock().lock();
        try {
            ConfigurationSection contextsSection = config.createSection("contexts");

            for (Map.Entry<String, Map<String, Map<String, Integer>>> typeEntry : globalStats.entrySet()) {
                String type = typeEntry.getKey();
                Map<String, Map<String, Integer>> typeData = typeEntry.getValue();

                ConfigurationSection typeSection = contextsSection.createSection(type);

                for (Map.Entry<String, Map<String, Integer>> categoryEntry : typeData.entrySet()) {
                    String category = categoryEntry.getKey();
                    Map<String, Integer> categoryData = categoryEntry.getValue();

                    int total = categoryData.getOrDefault("total", 0);
                    Map<String, Integer> items = new HashMap<>(categoryData);
                    items.remove("total");

                    if (total == 0 && items.isEmpty()) continue;

                    ConfigurationSection categorySection = typeSection.createSection(category);
                    categorySection.set("total", total);

                    if (!items.isEmpty()) {
                        ConfigurationSection itemsSection = categorySection.createSection("items");
                        for (Map.Entry<String, Integer> itemEntry : items.entrySet()) {
                            itemsSection.set(itemEntry.getKey(), itemEntry.getValue());
                        }
                    }
                }
            }

            config.save(globalStatsFile);
            if (enableAutoSaveLog) {
                logger.info("Global stats saved");
            }
        } catch (IOException e) {
            logger.severe("Failed to save global stats: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void autoSave() {
        if (!statsModified) return;

        lock.writeLock().lock();
        try {
            if (statsModified) {
                save();
                statsModified = false;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}