package CesarCosmico.commands.features;

import CesarCosmico.CustomFishingStats;
import CesarCosmico.commands.BaseCommand;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

@SuppressWarnings("UnstableApiUsage")
public class ReloadCommand extends BaseCommand implements Command<CommandSourceStack> {

    public ReloadCommand(CustomFishingStats plugin) {
        super(plugin);
    }

    @Override
    public int run(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();

        sendPrefixed(sender, "admin.reload.start");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.reloadPlugin();

                Bukkit.getScheduler().runTask(plugin, () ->
                        sendPrefixed(sender, "admin.reload.success")
                );
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sendPrefixed(sender, "admin.reload.error",
                                Placeholder.parsed("error", e.getMessage()))
                );
                plugin.getLogger().severe("Error during reload: " + e.getMessage());
                e.printStackTrace();
            }
        });

        return Command.SINGLE_SUCCESS;
    }
}