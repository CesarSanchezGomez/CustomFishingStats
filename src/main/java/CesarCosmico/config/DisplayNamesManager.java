package CesarCosmico.config;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public class DisplayNamesManager {

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage;
    private YamlConfiguration config;

    public DisplayNamesManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        loadConfig();
    }

    private void loadConfig() {
        File configFile = new File(plugin.getDataFolder(), "display_names.yml");

        if (!configFile.exists()) {
            try (InputStream in = plugin.getResource("display_names.yml")) {
                if (in != null) {
                    Files.copy(in, configFile.toPath());
                    plugin.getLogger().info("Created default display_names.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create display_names.yml: " + e.getMessage());
            }
        }

        config = YamlConfiguration.loadConfiguration(configFile);
    }

    public String getTypeDisplayString(String typeKey) {
        return config.getString("types." + typeKey, typeKey);
    }

    public String getCategoryDisplayString(String categoryKey) {
        return config.getString("categories." + categoryKey, categoryKey);
    }

    public String getUnitString(String typeKey) {
        return config.getString("units." + typeKey, "Pts.");
    }

    public void reload() {
        loadConfig();
    }
}