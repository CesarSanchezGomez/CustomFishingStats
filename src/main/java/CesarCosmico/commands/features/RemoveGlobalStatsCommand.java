package CesarCosmico.commands.features;

import CesarCosmico.CustomFishingStats;
import CesarCosmico.commands.BaseCommand;
import CesarCosmico.tracking.TrackingContext;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.util.concurrent.CompletableFuture;

@SuppressWarnings("UnstableApiUsage")
public class RemoveGlobalStatsCommand extends BaseCommand {

    public RemoveGlobalStatsCommand(CustomFishingStats plugin) {
        super(plugin);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> create(CustomFishingStats plugin) {
        RemoveGlobalStatsCommand cmd = new RemoveGlobalStatsCommand(plugin);

        return LiteralArgumentBuilder.<CommandSourceStack>literal("removeglobal")
                .requires(source -> source.getSender().hasPermission("customfishingstats.admin.removeglobal"))
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests(cmd::suggestTypes)
                        .then(Commands.argument("category", StringArgumentType.word())
                                .suggests(cmd::suggestCategories)
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> cmd.execute(ctx, null))
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .suggests(cmd::suggestItems)
                                                .executes(ctx -> cmd.execute(
                                                        ctx, StringArgumentType.getString(ctx, "item")))
                                        )
                                )
                        )
                );
    }

    private int execute(CommandContext<CommandSourceStack> ctx, String item) {
        try {
            CommandSender sender = ctx.getSource().getSender();
            String type = ctx.getArgument("type", String.class);
            String category = ctx.getArgument("category", String.class);
            int requestedAmount = ctx.getArgument("amount", Integer.class);

            TrackingContext context = buildTrackingContext(type, category, requestedAmount, item);

            plugin.removeStatsTransactional(null, context)
                    .thenAccept(actuallyRemoved -> {
                        if (actuallyRemoved > 0) {
                            sendSuccessMessage(sender, type, category, actuallyRemoved, item);
                        } else {
                            sendPrefixed(sender, "errors.global_has_no_stats");
                        }
                    })
                    .exceptionally(throwable -> {
                        plugin.getLogger().severe("Error removing global stats: " + throwable.getMessage());
                        sendPrefixed(sender, "errors.operation_failed");
                        return null;
                    });

            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            handleError(ctx, e);
            return 0;
        }
    }

    private TrackingContext buildTrackingContext(String type, String category, int amount, String item) {
        TrackingContext.Builder builder = TrackingContext.builder()
                .type(type)
                .category(category)
                .amount(amount);
        if (item != null) {
            builder.item(item);
        }
        return builder.build();
    }

    private void sendSuccessMessage(CommandSender sender, String type, String category,
                                    int amount, String item) {
        String path = (item != null) ? "admin.remove.success_global_item" : "admin.remove.success_global_category";
        sendPrefixed(sender, path,
                Placeholder.parsed("amount", messages.formatNumber(amount)),
                Placeholder.parsed("item", item != null ? item : ""),
                Placeholder.parsed("type", type),
                Placeholder.parsed("category", category)
        );
    }

    private void handleError(CommandContext<CommandSourceStack> ctx, Exception e) {
        sendPrefixed(ctx.getSource().getSender(), "errors.unknown_error",
                Placeholder.parsed("error", e.getMessage()));
        plugin.getLogger().severe("Error removing global stats: " + e.getMessage());
    }

    private CompletableFuture<Suggestions> suggestTypes(CommandContext<CommandSourceStack> ctx,
                                                        SuggestionsBuilder builder) {
        plugin.getAllTypes().forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestCategories(CommandContext<CommandSourceStack> ctx,
                                                             SuggestionsBuilder builder) {
        try {
            String type = ctx.getArgument("type", String.class);
            plugin.getCategoriesByType(type).forEach(builder::suggest);
        } catch (Exception ignored) {
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestItems(CommandContext<CommandSourceStack> ctx,
                                                        SuggestionsBuilder builder) {
        try {
            String type = ctx.getArgument("type", String.class);
            String category = ctx.getArgument("category", String.class);
            plugin.getCategoryItems(type, category).keySet().forEach(builder::suggest);
        } catch (Exception ignored) {
        }
        return builder.buildFuture();
    }
}