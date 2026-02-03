package CesarCosmico.commands;

import CesarCosmico.CustomFishingStats;
import CesarCosmico.config.MessagesManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.List;

public abstract class BaseCommand {

    protected final CustomFishingStats plugin;
    protected final MessagesManager messages;

    protected BaseCommand(CustomFishingStats plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
    }

    protected void sendMultiLineMessage(CommandSender sender, String path, TagResolver... resolvers) {
        List<Component> lines = messages.getList(path, resolvers);
        lines.forEach(sender::sendMessage);
    }

    protected void sendPrefixed(CommandSender sender, String path, TagResolver... resolvers) {
        Component message = messages.get(path, resolvers);
        Component prefixed = messages.getPrefix()
                .append(Component.space())
                .append(message);
        sender.sendMessage(prefixed);
    }
}