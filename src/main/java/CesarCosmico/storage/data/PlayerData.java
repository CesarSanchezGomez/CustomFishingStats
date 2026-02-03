package CesarCosmico.storage.data;

import CesarCosmico.tracking.TrackingContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerData {
    private final UUID uuid;
    private String name;

    private final Map<String, Map<String, Map<String, Integer>>> stats;

    private final Map<String, Integer> typeTotalCache;
    private volatile boolean typeCacheDirty = true;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.stats = new ConcurrentHashMap<>();
        this.typeTotalCache = new ConcurrentHashMap<>();
    }

    public void addStats(TrackingContext context) {
        String type = context.getType();
        String category = context.getCategory();
        int amount = context.getAmount();

        Map<String, Map<String, Integer>> typeData = stats.computeIfAbsent(type,
                k -> new ConcurrentHashMap<>());
        Map<String, Integer> categoryData = typeData.computeIfAbsent(category,
                k -> new ConcurrentHashMap<>());

        categoryData.merge("total", amount, Integer::sum);

        if (context.hasItem()) {
            categoryData.merge(context.getItem(), amount, Integer::sum);
        }

        typeCacheDirty = true;
        typeTotalCache.remove(type);
    }

    public void removeStats(TrackingContext context) {
        String type = context.getType();
        String category = context.getCategory();
        int amount = context.getAmount();

        Map<String, Map<String, Integer>> typeData = stats.get(type);
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

        if (categoryData.isEmpty()) {
            typeData.remove(category);
        }
        if (typeData.isEmpty()) {
            stats.remove(type);
        }

        typeCacheDirty = true;
        typeTotalCache.remove(type);
    }

    public int getCategoryTotal(String type, String category) {
        Map<String, Map<String, Integer>> typeData = stats.get(type);
        if (typeData == null) return 0;
        Map<String, Integer> categoryData = typeData.get(category);
        if (categoryData == null) return 0;
        return categoryData.getOrDefault("total", 0);
    }

    public int getItemAmount(String type, String category, String itemId) {
        Map<String, Map<String, Integer>> typeData = stats.get(type);
        if (typeData == null) return 0;
        Map<String, Integer> categoryData = typeData.get(category);
        if (categoryData == null) return 0;
        return categoryData.getOrDefault(itemId, 0);
    }

    public int getTotalByType(String type) {
        if (!typeCacheDirty && typeTotalCache.containsKey(type)) {
            return typeTotalCache.get(type);
        }

        Map<String, Map<String, Integer>> typeData = stats.get(type);
        if (typeData == null) {
            typeTotalCache.put(type, 0);
            return 0;
        }

        int total = typeData.values().stream()
                .mapToInt(categoryData -> categoryData.getOrDefault("total", 0))
                .sum();

        typeTotalCache.put(type, total);
        return total;
    }

    public Set<String> getAllTypes() {
        return new HashSet<>(stats.keySet());
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Map<String, Map<String, Integer>>> getStatsInternal() {
        return stats;
    }

    @Override
    public String toString() {
        return String.format("PlayerData{uuid=%s, name=%s, types=%d}",
                uuid, name, stats.size());
    }
}