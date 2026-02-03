package CesarCosmico.commands;

import CesarCosmico.CustomFishingStats;
import CesarCosmico.commands.features.*;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/**
 * Gestor central de comandos del plugin.
 * Responsabilidad única: Registrar y organizar la estructura de comandos.
 */
public class CommandManager {

    private final CustomFishingStats plugin;

    public CommandManager(CustomFishingStats plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> createCommand() {
        return Commands.literal("pescastats")
                .executes(new HelpCommand(plugin))
                .then(createHelpCommand())
                .then(TopCommand.create(plugin))
                .then(createAdminCommands())
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> createHelpCommand() {
        return Commands.literal("help")
                .requires(source -> source.getSender().hasPermission("customfishingstats.help"))
                .executes(new HelpCommand(plugin))
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> createAdminCommands() {
        return Commands.literal("admin")
                .requires(source -> source.getSender().hasPermission("customfishingstats.admin"))
                .then(createReloadCommand())
                .then(AddPlayerStatsCommand.create(plugin))
                .then(RemovePlayerStatsCommand.create(plugin))
                .then(AddGlobalStatsCommand.create(plugin))
                .then(RemoveGlobalStatsCommand.create(plugin))
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> createReloadCommand() {
        return Commands.literal("reload")
                .requires(source -> source.getSender().hasPermission("customfishingstats.admin.reload"))
                .executes(new ReloadCommand(plugin))
                .build();
    }
}