package CesarCosmico.storage.data;

import CesarCosmico.tracking.TrackingContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sistema de almacenamiento jerárquico y flexible
 * <p>
 * Estructura jerárquica:
 * - Map<Type, Map<Category, CategoryData>>
 * - Type = "competition", "recycling", "recovery", etc.
 * - Category = "suma_total", "legendary", "common", etc.
 * - CategoryData = {
 *     "total": cantidad total de puntos,
 *     "item_id": cantidad del item (opcional)
 *   }
 * <p>
 * Ejemplos:
 * - "competition" -> {"suma_total" -> {"total": 500}}
 * - "recycling" -> {"legendary" -> {"total": 150, "ancient_sword": 50, "divine_rod": 100}}
 * - "event" -> {"halloween" -> {"total": 200, "candy": 200}}
 */
public class PlayerData {
    private final UUID uuid;
    private String name;
    // Estructura: Map<Type, Map<Category, Map<"item_or_total", count>>>
    private final Map<String, Map<String, Map<String, Integer>>> stats;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.stats = new ConcurrentHashMap<>();
    }

    // ========================================
    // CORE OPERATIONS
    // ========================================

    /**
     * Añadir stats usando TrackingContext
     */
    public void addStats(TrackingContext context) {
        String type = context.getType();
        String category = context.getCategory();
        int amount = context.getAmount();

        Map<String, Map<String, Integer>> typeData = stats.computeIfAbsent(type,
                k -> new ConcurrentHashMap<>());
        Map<String, Integer> categoryData = typeData.computeIfAbsent(category,
                k -> new ConcurrentHashMap<>());

        // Siempre actualizar el total
        categoryData.merge("total", amount, Integer::sum);

        // Si hay item específico, también actualizarlo
        if (context.hasItem()) {
            categoryData.merge(context.getItem(), amount, Integer::sum);
        }
    }

    /**
     * Remover stats usando TrackingContext
     */
    public void removeStats(TrackingContext context) {
        String type = context.getType();
        String category = context.getCategory();
        int amount = context.getAmount();

        Map<String, Map<String, Integer>> typeData = stats.get(type);
        if (typeData == null) return;

        Map<String, Integer> categoryData = typeData.get(category);
        if (categoryData == null) return;

        // Actualizar total
        int currentTotal = categoryData.getOrDefault("total", 0);
        int newTotal = Math.max(0, currentTotal - amount);
        if (newTotal > 0) {
            categoryData.put("total", newTotal);
        } else {
            categoryData.remove("total");
        }

        // Si hay item específico, actualizarlo también
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

        // Limpiar estructuras vacías
        if (categoryData.isEmpty()) {
            typeData.remove(category);
        }
        if (typeData.isEmpty()) {
            stats.remove(type);
        }
    }

    // ========================================
    // QUERY METHODS
    // ========================================

    /**
     * Obtener total de puntos de un type:category
     */
    public int getCategoryTotal(String type, String category) {
        Map<String, Map<String, Integer>> typeData = stats.get(type);
        if (typeData == null) return 0;
        Map<String, Integer> categoryData = typeData.get(category);
        if (categoryData == null) return 0;
        return categoryData.getOrDefault("total", 0);
    }

    /**
     * Obtener cantidad de un item específico en un type:category
     */
    public int getItemAmount(String type, String category, String itemId) {
        Map<String, Map<String, Integer>> typeData = stats.get(type);
        if (typeData == null) return 0;
        Map<String, Integer> categoryData = typeData.get(category);
        if (categoryData == null) return 0;
        return categoryData.getOrDefault(itemId, 0);
    }

    /**
     * Obtener total de todas las categorías de un tipo
     */
    public int getTotalByType(String type) {
        Map<String, Map<String, Integer>> typeData = stats.get(type);
        if (typeData == null) return 0;
        return typeData.values().stream()
                .mapToInt(categoryData -> categoryData.getOrDefault("total", 0))
                .sum();
    }

    /**
     * Obtener todas las categorías de un tipo específico
     *
     * @param type El tipo del que se quieren obtener las categorías
     * @return Set de nombres de categorías
     */
    public Set<String> getCategoriesByType(String type) {
        Map<String, Map<String, Integer>> typeData = stats.get(type);
        if (typeData == null) {
            return new HashSet<>();
        }
        return new HashSet<>(typeData.keySet());
    }

    /**
     * Obtener todos los tipos registrados
     */
    public Set<String> getAllTypes() {
        return new HashSet<>(stats.keySet());
    }

    // ========================================
    // GETTERS Y INTERNAL ACCESS
    // ========================================

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Internal access for YAMLProvider
     * Returns direct reference to internal stats structure
     */
    public Map<String, Map<String, Map<String, Integer>>> getStatsInternal() {
        return stats;
    }
}