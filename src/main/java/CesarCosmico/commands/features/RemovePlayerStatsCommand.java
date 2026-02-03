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
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings("UnstableApiUsage")
public class RemovePlayerStatsCommand extends BaseCommand {

    public RemovePlayerStatsCommand(CustomFishingStats plugin) {
        super(plugin);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> create(CustomFishingStats plugin) {
        RemovePlayerStatsCommand cmd = new RemovePlayerStatsCommand(plugin);

        return LiteralArgumentBuilder.<CommandSourceStack>literal("removeplayer")
                .requires(source -> source.getSender().hasPermission("customfishingstats.admin.removeplayer"))
                .then(Commands.argument("player", StringArgumentType.string())
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
                        )
                );
    }

    private int execute(CommandContext<CommandSourceStack> ctx, String item) {
        try {
            CommandSender sender = ctx.getSource().getSender();
            String playerInput = ctx.getArgument("player", String.class);
            String type = ctx.getArgument("type", String.class);
            String category = ctx.getArgument("category", String.class);
            int requestedAmount = ctx.getArgument("amount", Integer.class);

            PlayerResolver resolver = resolvePlayer(playerInput);
            if (resolver == null) {
                sendPrefixed(sender, "errors.player_not_found");
                return 0;
            }

            TrackingContext context = buildTrackingContext(type, category, requestedAmount, item);

            plugin.removeStatsTransactional(resolver.uuid, context)
                    .thenAccept(actuallyRemoved -> {
                        if (actuallyRemoved > 0) {
                            sendSuccessMessage(sender, resolver.displayName, type, category,
                                    actuallyRemoved, item);
                        } else {
                            sendPrefixed(sender, "errors.player_has_no_stats");
                        }
                    })
                    .exceptionally(throwable -> {
                        plugin.getLogger().severe("Error removing stats: " + throwable.getMessage());
                        sendPrefixed(sender, "errors.operation_failed");
                        return null;
                    });

            return Command.SINGLE_SUCCESS;

        } catch (Exception e) {
            handleError(ctx, e);
            return 0;
        }
    }

    private PlayerResolver resolvePlayer(String playerInput) {
        Player onlinePlayer = Bukkit.getPlayerExact(playerInput);
        if (onlinePlayer != null) {
            return new PlayerResolver(onlinePlayer.getUniqueId(), onlinePlayer.getName());
        }

        try {
            UUID uuid = UUID.fromString(playerInput);
            return resolveOfflinePlayer(uuid);
        } catch (IllegalArgumentException e) {
            return resolveOfflinePlayerByName(playerInput);
        }
    }

    @SuppressWarnings("deprecation")
    private PlayerResolver resolveOfflinePlayer(UUID uuid) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        String displayName = offlinePlayer.getName() != null ? offlinePlayer.getName() : uuid.toString();
        return new PlayerResolver(uuid, displayName);
    }

    @SuppressWarnings("deprecation")
    private PlayerResolver resolveOfflinePlayerByName(String playerInput) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerInput);

        if (!offlinePlayer.hasPlayedBefore() && !offlinePlayer.isOnline()) {
            return null;
        }

        String displayName = offlinePlayer.getName() != null ? offlinePlayer.getName() : playerInput;
        return new PlayerResolver(offlinePlayer.getUniqueId(), displayName);
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

    private void sendSuccessMessage(CommandSender sender, String displayName,
                                    String type, String category, int amount, String item) {
        String path = (item != null) ? "admin.remove.success_item" : "admin.remove.success_category";
        sendPrefixed(sender, path,
                Placeholder.parsed("amount", messages.formatNumber(amount)),
                Placeholder.parsed("item", item != null ? item : ""),
                Placeholder.parsed("type", type),
                Placeholder.parsed("category", category),
                Placeholder.parsed("player", displayName)
        );
    }

    private void handleError(CommandContext<CommandSourceStack> ctx, Exception e) {
        sendPrefixed(ctx.getSource().getSender(), "errors.unknown_error",
                Placeholder.parsed("error", e.getMessage()));
        plugin.getLogger().severe("Error removing player stats: " + e.getMessage());
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

    private static class PlayerResolver {
        final UUID uuid;
        final String displayName;

        PlayerResolver(UUID uuid, String displayName) {
            this.uuid = uuid;
            this.displayName = displayName;
        }
    }
}