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
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

@SuppressWarnings("UnstableApiUsage")
public class AddPlayerStatsCommand extends BaseCommand {

    public AddPlayerStatsCommand(CustomFishingStats plugin) {
        super(plugin);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> create(CustomFishingStats plugin) {
        return LiteralArgumentBuilder.<CommandSourceStack>literal("addplayer")
                .requires(source -> source.getSender().hasPermission("customfishingstats.admin.addplayer"))
                .then(Commands.argument("player", StringArgumentType.string())
                        .then(Commands.argument("type", StringArgumentType.word())
                                .then(Commands.argument("category", StringArgumentType.word())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(ctx -> new AddPlayerStatsCommand(plugin).execute(ctx, null))
                                                .then(Commands.argument("item", StringArgumentType.word())
                                                        .executes(ctx -> new AddPlayerStatsCommand(plugin).execute(
                                                                ctx, StringArgumentType.getString(ctx, "item")))
                                                )
                                        )
                                )
                        )
                );
    }

    private int execute(CommandContext<CommandSourceStack> ctx, String item) {
        try {
            String playerInput = ctx.getArgument("player", String.class);
            String type = ctx.getArgument("type", String.class);
            String category = ctx.getArgument("category", String.class);
            int amount = ctx.getArgument("amount", Integer.class);

            PlayerResolver resolver = resolvePlayer(playerInput);
            if (resolver == null) {
                sendPrefixed(ctx.getSource().getSender(), "errors.player_never_joined",
                        Placeholder.parsed("player", playerInput));
                return 0;
            }

            FishingStatContext context = buildTrackingContext(type, category, amount, item);

            // FIXED: Uso de método transaccional
            plugin.addStatsTransactional(resolver.uuid, context)
                    .thenRun(() -> {
                        sendSuccessMessage(ctx, resolver.displayName, type, category, amount, item);
                    })
                    .exceptionally(throwable -> {
                        plugin.getLogger().severe("Error adding stats: " + throwable.getMessage());
                        sendPrefixed(ctx.getSource().getSender(), "errors.operation_failed");
                        return null;
                    });

            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            handleError(ctx, e);
            return 0;
        }
    }

    private PlayerResolver resolvePlayer(String playerInput) {
        Player onlinePlayer = Bukkit.getPlayerExact(playerInput);
        if (onlinePlayer != null) {
            return new PlayerResolver(onlinePlayer.getUniqueId(), onlinePlayer.getName(), true);
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
        return new PlayerResolver(uuid, displayName, false);
    }

    @SuppressWarnings("deprecation")
    private PlayerResolver resolveOfflinePlayerByName(String playerInput) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerInput);

        if (!offlinePlayer.hasPlayedBefore() && !offlinePlayer.isOnline()) {
            return null;
        }

        String displayName = offlinePlayer.getName() != null ? offlinePlayer.getName() : playerInput;
        return new PlayerResolver(offlinePlayer.getUniqueId(), displayName, false);
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

    private void sendSuccessMessage(CommandContext<CommandSourceStack> ctx, String displayName,
                                    String type, String category, int amount, String item) {
        String path = (item != null) ? "admin.add.success_item" : "admin.add.success_category";
        sendPrefixed(ctx.getSource().getSender(), path,
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
        plugin.getLogger().severe("Error adding player stats: " + e.getMessage());
    }

    private static class PlayerResolver {
        final UUID uuid;
        final String displayName;

        PlayerResolver(UUID uuid, String displayName, boolean isOnline) {
            this.uuid = uuid;
            this.displayName = displayName;
        }
    }
}