package CesarCosmico.commands.features;

import CesarCosmico.CustomFishingStats;
import CesarCosmico.commands.BaseCommand;
import CesarCosmico.actions.FishingStatContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

@SuppressWarnings("UnstableApiUsage")
public class AddGlobalStatsCommand extends BaseCommand {

    public AddGlobalStatsCommand(CustomFishingStats plugin) {
        super(plugin);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> create(CustomFishingStats plugin) {
        return LiteralArgumentBuilder.<CommandSourceStack>literal("addglobal")
                .requires(source -> source.getSender().hasPermission("customfishingstats.admin.addglobal"))
                .then(Commands.argument("type", StringArgumentType.word())
                        .then(Commands.argument("category", StringArgumentType.word())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> new AddGlobalStatsCommand(plugin).execute(ctx, null))
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .executes(ctx -> new AddGlobalStatsCommand(plugin).execute(
                                                        ctx, StringArgumentType.getString(ctx, "item")))
                                        )
                                )
                        )
                );
    }

    private int execute(CommandContext<CommandSourceStack> ctx, String item) {
        try {
            String type = ctx.getArgument("type", String.class);
            String category = ctx.getArgument("category", String.class);
            int amount = ctx.getArgument("amount", Integer.class);

            FishingStatContext context = buildTrackingContext(type, category, amount, item);

            plugin.addStatsTransactional(null, context)
                    .thenRun(() -> {
                        sendSuccessMessage(ctx, type, category, amount, item);
                    })
                    .exceptionally(throwable -> {
                        plugin.getLogger().severe("Error adding global stats: " + throwable.getMessage());
                        sendPrefixed(ctx.getSource().getSender(), "errors.operation_failed");
                        return null;
                    });

            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            handleError(ctx, e);
            return 0;
        }
    }

    private FishingStatContext buildTrackingContext(String type, String category, int amount, String item) {
        FishingStatContext.Builder builder = FishingStatContext.builder()
                .type(type)
                .category(category)
                .amount(amount);
        if (item != null) {
            builder.item(item);
        }
        return builder.build();
    }

    private void sendSuccessMessage(CommandContext<CommandSourceStack> ctx,
                                    String type, String category, int amount, String item) {
        String path = (item != null) ? "admin.add.success_global_item" : "admin.add.success_global_category";
        sendPrefixed(ctx.getSource().getSender(), path,
                Placeholder.parsed("amount", messages.formatNumber(amount)),
                Placeholder.parsed("item", item != null ? item : ""),
                Placeholder.parsed("type", type),
                Placeholder.parsed("category", category)
        );
    }

    private void handleError(CommandContext<CommandSourceStack> ctx, Exception e) {
        sendPrefixed(ctx.getSource().getSender(), "errors.unknown_error",
                Placeholder.parsed("error", e.getMessage()));
        plugin.getLogger().severe("Error adding global stats: " + e.getMessage());
    }
}