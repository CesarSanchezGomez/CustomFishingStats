package CesarCosmico.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/**
 * Gestor de configuración del plugin.
 * Responsabilidad única: Gestionar la carga y acceso a la configuración.
 *
 * Aplica:
 * - Single Responsibility: Solo maneja configuración
 * - Open/Closed: Extensible mediante nuevas opciones de config
 */
public class ConfigManager {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private File configFile;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        loadConfig();
    }

    private void loadConfig() {
        if (!configFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                configFile.createNewFile();
                writeDefaultConfig(configFile);
                plugin.getLogger().info("Created default config.yml");
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to create config.yml: " + e.getMessage());
            }
        }

        this.config = YamlConfiguration.loadConfiguration(configFile);
        plugin.reloadConfig();
    }

    private void writeDefaultConfig(File configFile) {
        FileConfiguration defaultConfig = new YamlConfiguration();

        defaultConfig.set("language", "en_US");
        defaultConfig.setComments("language", List.of(
                "Language file to use from translations/ folder",
                "Available: es_ES, en_US, pt_BR, etc.",
                "Create custom languages by copying and editing language files"
        ));

        defaultConfig.set("storage.auto-save.enabled", true);
        defaultConfig.setComments("storage.auto-save.enabled", List.of(
                "Enable automatic saving of player data and global stats"
        ));

        defaultConfig.set("storage.auto-save.interval", 300);
        defaultConfig.setComments("storage.auto-save.interval", List.of(
                "Auto-save interval in seconds",
                "Saves both player data and global statistics",
                "Default: 300 seconds (5 minutes)"
        ));

        defaultConfig.set("storage.auto-save.log", true);
        defaultConfig.setComments("storage.auto-save.log", List.of(
                "Log auto-save operations to console"
        ));

        defaultConfig.setComments("storage", List.of(
                "Storage and auto-save configuration"
        ));

        try {
            defaultConfig.save(configFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save default config: " + e.getMessage());
        }
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(configFile);
        plugin.reloadConfig();
    }
}