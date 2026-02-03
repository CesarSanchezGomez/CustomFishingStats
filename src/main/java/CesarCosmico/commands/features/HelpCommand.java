package CesarCosmico.commands.features;

import CesarCosmico.CustomFishingStats;
import CesarCosmico.commands.BaseCommand;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

/**
 * Comando público para mostrar ayuda.
 * Responsabilidad única: Mostrar información de ayuda al usuario.
 */
public class HelpCommand extends BaseCommand implements Command<CommandSourceStack> {

    public HelpCommand(CustomFishingStats plugin) {
        super(plugin);
    }

    @Override
    public int run(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sendMultiLineMessage(sender, "help.display");
        return Command.SINGLE_SUCCESS;
    }
}