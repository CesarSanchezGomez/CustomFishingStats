package CesarCosmico.storage.method.file;

import CesarCosmico.storage.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * YAML Storage provider con sistema jerárquico de contextos
 * <p>
 * Estructura del archivo YAML:
 * <p>
 * name: "PlayerName"
 * contexts:
 *   recycling:
 *     singular:
 *       total: 21
 *       items:
 *         bambu: 21
 *     common:
 *       total: 28
 *       items:
 *         bambu: 28
 *   competition:
 *     suma_total:
 *       total: 11
 *   recovery:
 *     common:
 *       total: 12
 *       items:
 *         bambu: 12
 */
public class YAMLProvider {
    private final Plugin plugin;
    private final File dataFolder;

    public YAMLProvider(Plugin plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "storage/data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    // ========================================
    // LOAD
    // ========================================
    public CompletableFuture<PlayerData> loadPlayerData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            File playerFile = getPlayerFile(uuid);
            if (!playerFile.exists()) {
                String playerName = Bukkit.getOfflinePlayer(uuid).getName();
                return new PlayerData(uuid, playerName != null ? playerName : "Unknown");
            }
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
                String name = config.getString("name", "Unknown");
                PlayerData playerData = new PlayerData(uuid, name);
                loadContexts(playerData, config);
                return playerData;
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error loading player data for " + uuid, e);
                return new PlayerData(uuid, "Unknown");
            }
        });
    }

    /**
     * Cargar todos los contextos desde YAML con estructura jerárquica
     */
    private void loadContexts(PlayerData playerData, YamlConfiguration config) {
        ConfigurationSection contextsSection = config.getConfigurationSection("contexts");
        if (contextsSection == null) return;

        for (String type : contextsSection.getKeys(false)) {
            ConfigurationSection typeSection = contextsSection.getConfigurationSection(type);
            if (typeSection == null) continue;

            for (String category : typeSection.getKeys(false)) {
                ConfigurationSection categorySection = typeSection.getConfigurationSection(category);
                if (categorySection == null) continue;

                Map<String, Integer> data = new ConcurrentHashMap<>();
                data.put("total", categorySection.getInt("total", 0));

                ConfigurationSection itemsSection = categorySection.getConfigurationSection("items");
                if (itemsSection != null) {
                    for (String itemId : itemsSection.getKeys(false)) {
                        data.put(itemId, itemsSection.getInt(itemId, 0));
                    }
                }

                playerData.getStatsInternal()
                        .computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                        .put(category, data);
            }
        }
    }

    // ========================================
    // SAVE
    // ========================================
    public CompletableFuture<Boolean> savePlayerData(PlayerData playerData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                YamlConfiguration config = new YamlConfiguration();
                config.set("name", playerData.getName());
                // Guardar todos los contextos
                saveContexts(playerData, config);
                config.save(getPlayerFile(playerData.getUuid()));
                return true;
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Error saving player data for " + playerData.getUuid(), e);
                return false;
            }
        });
    }

    /**
     * Guardar todos los contextos en YAML con estructura jerárquica
     */
    private void saveContexts(PlayerData playerData, YamlConfiguration config) {
        ConfigurationSection contextsSection = config.createSection("contexts");
        Map<String, Map<String, Map<String, Integer>>> allStats = playerData.getStatsInternal();

        for (Map.Entry<String, Map<String, Map<String, Integer>>> typeEntry : allStats.entrySet()) {
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
    }

    private File getPlayerFile(UUID uuid) {
        return new File(dataFolder, uuid.toString() + ".yml");
    }
}