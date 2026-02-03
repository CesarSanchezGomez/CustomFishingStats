package CesarCosmico.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class MessagesManager {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage;
    private YamlConfiguration messages;
    private String currentLanguage;

    public MessagesManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        loadMessages();
    }

    private void loadMessages() {
        this.currentLanguage = plugin.getConfig().getString("language", "en_US");

        File translationsFolder = new File(plugin.getDataFolder(), "translations");
        File messagesFile = new File(translationsFolder, currentLanguage + ".yml");


        if (!translationsFolder.exists()) {
            translationsFolder.mkdirs();
        }

        if (!messagesFile.exists()) {
            createDefaultLanguageFile(messagesFile);
        }

        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }


    private void createDefaultLanguageFile(File messagesFile) {
        String resourcePath = "translations/" + currentLanguage + ".yml";

        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in != null) {
                Files.copy(in, messagesFile.toPath());
                plugin.getLogger().info("Created language file: " + currentLanguage + ".yml");
            } else {
                plugin.getLogger().warning("Language file not found in resources: " + resourcePath);
                plugin.getLogger().info("Creating fallback language file (es_ES)...");

                try (InputStream fallback = plugin.getResource("translations/es_ES.yml")) {
                    if (fallback != null) {
                        Files.copy(fallback, messagesFile.toPath());
                        plugin.getLogger().info("Created fallback language file: " + currentLanguage + ".yml");
                    } else {
                        plugin.getLogger().severe("No language files found in plugin resources!");
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to create language file: " + e.getMessage());
        }
    }

    public Component get(String path, TagResolver... resolvers) {
        if (messages.isList(path)) {
            List<String> lines = messages.getStringList(path);
            if (lines.isEmpty()) {
                return miniMessage.deserialize("<red>Missing: " + path);
            }
            String joined = String.join("\n", lines);
            return miniMessage.deserialize(joined, resolvers);
        }

        String raw = messages.getString(path, "<red>Missing: " + path);
        return miniMessage.deserialize(raw, resolvers);
    }

    public List<Component> getList(String path, TagResolver... resolvers) {
        List<String> lines = messages.getStringList(path);
        List<Component> components = new ArrayList<>();
        for (String line : lines) {
            components.add(miniMessage.deserialize(line, resolvers));
        }
        return components;
    }

    public Component getPrefix() {
        String raw = messages.getString("prefix", "<gray>[CustomFishingStats]</gray>");
        return miniMessage.deserialize(raw);
    }

    public String formatNumber(int number) {
        return String.format("%,d", number);
    }

    public void reload() {
        loadMessages();
    }
}