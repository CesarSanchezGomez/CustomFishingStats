package CesarCosmico.services;

import CesarCosmico.tracking.TrackingContext;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class GlobalStatsService {

    private final Logger logger;
    private final File storageFolder;
    private final boolean enableAutoSaveLog;

    private final Map<String, Map<String, Map<String, Integer>>> globalStats = new ConcurrentHashMap<>();
    private volatile boolean statsModified = false;

    public GlobalStatsService(Logger logger, File dataFolder, boolean enableAutoSaveLog) {
        this.logger = logger;
        this.storageFolder = new File(dataFolder, "storage");
        this.enableAutoSaveLog = enableAutoSaveLog;

        if (!storageFolder.exists()) {
            storageFolder.mkdirs();
        }
    }

    public void increment(TrackingContext context) {
        String type = context.getType();
        String category = context.getCategory();
        int amount = context.getAmount();

        Map<String, Map<String, Integer>> typeData = globalStats.computeIfAbsent(type,
                k -> new ConcurrentHashMap<>());
        Map<String, Integer> categoryData = typeData.computeIfAbsent(category,
                k -> new ConcurrentHashMap<>());

        categoryData.merge("total", amount, Integer::sum);

        if (context.hasItem()) {
            categoryData.merge(context.getItem(), amount, Integer::sum);
        }

        statsModified = true;
    }

    public void decrement(TrackingContext context) {
        String type = context.getType();
        String category = context.getCategory();
        int amount = context.getAmount();

        Map<String, Map<String, Integer>> typeData = globalStats.get(type);
        if (typeData == null) return;

        Map<String, Integer> categoryData = typeData.get(category);
        if (categoryData == null) return;

        categoryData.put("total", Math.max(0, categoryData.getOrDefault("total", 0) - amount));

        if (context.hasItem()) {
            String itemId = context.getItem();
            categoryData.put(itemId, Math.max(0, categoryData.getOrDefault(itemId, 0) - amount));
            if (categoryData.get(itemId) == 0) {
                categoryData.remove(itemId);
            }
        }

        cleanupEmptyStats(type, category, typeData, categoryData);
        statsModified = true;
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
        Map<String, Map<String, Integer>> typeData = globalStats.get(type);
        if (typeData == null) return 0;
        Map<String, Integer> categoryData = typeData.get(category);
        if (categoryData == null) return 0;
        return categoryData.getOrDefault("total", 0);
    }

    public Map<String, Integer> getCategoryItems(String type, String category) {
        Map<String, Map<String, Integer>> typeData = globalStats.get(type);
        if (typeData == null) return new HashMap<>();
        Map<String, Integer> categoryData = typeData.get(category);
        if (categoryData == null) return new HashMap<>();
        Map<String, Integer> items = new HashMap<>(categoryData);
        items.remove("total");
        return items;
    }

    public Set<String> getCategoriesByType(String type) {
        Map<String, Map<String, Integer>> typeData = globalStats.get(type);
        if (typeData == null) return new HashSet<>();
        return new HashSet<>(typeData.keySet());
    }

    public Set<String> getAllTypes() {
        return new HashSet<>(globalStats.keySet());
    }

    public int getTotalByType(String type) {
        Map<String, Map<String, Integer>> typeData = globalStats.get(type);
        if (typeData == null) return 0;
        return typeData.values().stream()
                .mapToInt(categoryData -> categoryData.getOrDefault("total", 0))
                .sum();
    }

    public void load() {
        File globalStatsFile = new File(storageFolder, "global_stats.yml");
        if (!globalStatsFile.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(globalStatsFile);
        ConfigurationSection contextsSection = config.getConfigurationSection("contexts");

        if (contextsSection == null) {
            loadLegacyFormat(config);
            return;
        }

        loadHierarchicalFormat(contextsSection);
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

        try {
            config.save(globalStatsFile);
            if (enableAutoSaveLog) {
                logger.info("Global stats saved");
            }
        } catch (IOException e) {
            logger.severe("Failed to save global stats: " + e.getMessage());
        }
    }

    public void autoSave() {
        if (statsModified) {
            save();
            statsModified = false;
        }
    }
}