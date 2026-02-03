package CesarCosmico.commands.features;

import CesarCosmico.CustomFishingStats;
import CesarCosmico.commands.BaseCommand;
import CesarCosmico.services.RankingService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("UnstableApiUsage")
public class TopCommand extends BaseCommand {

    private static final int ENTRIES_PER_PAGE = 10;

    public TopCommand(CustomFishingStats plugin) {
        super(plugin);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> create(CustomFishingStats plugin) {
        SuggestionProvider<CommandSourceStack> typeSuggestions = (context, builder) -> {
            builder.suggest("progress");
            plugin.getAllTypes().forEach(builder::suggest);
            return builder.buildFuture();
        };

        SuggestionProvider<CommandSourceStack> categorySuggestions = (context, builder) -> {
            try {
                String type = context.getArgument("type", String.class);

                if (type.equalsIgnoreCase("progress")) {
                    plugin.getRankingService().getCustomFishingCategories()
                            .forEach(builder::suggest);
                } else {
                    builder.suggest("all");
                    List<String> categories = plugin.getCategoriesByType(type);
                    if (categories != null) {
                        categories.forEach(builder::suggest);
                    }
                }
            } catch (Exception e) {
                builder.suggest("all");
            }
            return builder.buildFuture();
        };

        return LiteralArgumentBuilder.<CommandSourceStack>literal("top")
                .requires(source -> source.getSender().hasPermission("customfishingstats.top"))
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests(typeSuggestions)
                        .then(Commands.argument("category", StringArgumentType.word())
                                .suggests(categorySuggestions)
                                .executes(ctx -> new TopCommand(plugin).execute(ctx, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> new TopCommand(plugin).execute(
                                                ctx,
                                                ctx.getArgument("page", Integer.class)
                                        ))
                                )
                        )
                );
    }

    private int execute(CommandContext<CommandSourceStack> ctx, int page) {
        try {
            String type = ctx.getArgument("type", String.class);
            String category = ctx.getArgument("category", String.class);

            if (type.equalsIgnoreCase("progress")) {
                return executeProgress(ctx, category, page);
            } else {
                return executeRegular(ctx, type, category, page);
            }

        } catch (Exception e) {
            handleError(ctx, e);
            return 0;
        }
    }

    private int executeRegular(CommandContext<CommandSourceStack> ctx, String type,
                               String category, int page) {
        if (!validateType(ctx, type)) return 0;

        boolean isAll = category.equalsIgnoreCase("all");
        List<RankingService.PlayerRankEntry> allPlayers = fetchRegularPlayers(ctx, type, category, isAll);

        if (allPlayers == null || allPlayers.isEmpty()) return 0;
        if (!validatePage(ctx, page, allPlayers.size())) return 0;

        displayRanking(ctx, type, category, page, allPlayers, isAll);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeProgress(CommandContext<CommandSourceStack> ctx, String category, int page) {
        if (!validateProgressCategory(ctx, category)) return 0;

        List<RankingService.PlayerProgressEntry> allPlayersProgress = fetchProgressPlayers(ctx, category);
        if (allPlayersProgress == null || allPlayersProgress.isEmpty()) return 0;

        List<RankingService.PlayerRankEntry> allPlayers = allPlayersProgress.stream()
                .map(entry -> new RankingService.PlayerRankEntry(
                        entry.getUuid(),
                        entry.getName(),
                        (int) Math.round(entry.getProgress() * 10)
                ))
                .toList();

        if (!validatePage(ctx, page, allPlayers.size())) return 0;

        displayRanking(ctx, "progress", category, page, allPlayers, false);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private boolean validateType(CommandContext<CommandSourceStack> ctx, String type) {
        List<String> allTypes = plugin.getAllTypes();
        if (!allTypes.contains(type)) {
            sendPrefixed(ctx.getSource().getSender(), "top.invalid_type",
                    Placeholder.parsed("type", type));
            return false;
        }
        return true;
    }

    private boolean validateProgressCategory(CommandContext<CommandSourceStack> ctx, String category) {
        if (!plugin.getRankingService().isValidCustomFishingCategory(category)) {
            sendPrefixed(ctx.getSource().getSender(), "top.invalid_category",
                    Placeholder.parsed("category", category));
            return false;
        }
        return true;
    }

    private boolean validatePage(CommandContext<CommandSourceStack> ctx, int page, int totalEntries) {
        int totalPages = (int) Math.ceil((double) totalEntries / ENTRIES_PER_PAGE);

        if (page > totalPages) {
            sendPrefixed(ctx.getSource().getSender(), "top.invalid_page",
                    Placeholder.parsed("page", String.valueOf(page)),
                    Placeholder.parsed("max_page", String.valueOf(totalPages)));
            return false;
        }
        return true;
    }

    private List<RankingService.PlayerRankEntry> fetchRegularPlayers(CommandContext<CommandSourceStack> ctx,
                                                                     String type, String category, boolean isAll) {
        List<RankingService.PlayerRankEntry> players;

        if (isAll) {
            players = plugin.getRankingService().getTopPlayersByTypeWithUUID(type, Integer.MAX_VALUE);
        } else if (type.equals("category")) {
            if (!plugin.getRankingService().isValidCustomFishingCategory(category)) {
                sendPrefixed(ctx.getSource().getSender(), "top.invalid_category",
                        Placeholder.parsed("type", type),
                        Placeholder.parsed("category", category));
                return null;
            }
            players = plugin.getRankingService().getTopPlayersWithUUID(type, category, Integer.MAX_VALUE);
        } else {
            List<String> availableCategories = plugin.getCategoriesByType(type);
            if (availableCategories == null || !availableCategories.contains(category)) {
                sendPrefixed(ctx.getSource().getSender(), "top.invalid_category",
                        Placeholder.parsed("type", type),
                        Placeholder.parsed("category", category));
                return null;
            }
            players = plugin.getRankingService().getTopPlayersWithUUID(type, category, Integer.MAX_VALUE);
        }

        if (players.isEmpty()) {
            sendPrefixed(ctx.getSource().getSender(), "top.no_data",
                    Placeholder.parsed("type", type),
                    Placeholder.parsed("category", isAll ? "all" : category));
            return null;
        }

        return players;
    }

    private List<RankingService.PlayerProgressEntry> fetchProgressPlayers(CommandContext<CommandSourceStack> ctx,
                                                                          String category) {
        List<RankingService.PlayerProgressEntry> players =
                plugin.getRankingService().getTopPlayersByProgressWithUUID(category, Integer.MAX_VALUE);

        if (players.isEmpty()) {
            sendPrefixed(ctx.getSource().getSender(), "top.no_data_progress",
                    Placeholder.parsed("category", category));
            return null;
        }

        return players;
    }

    private void displayRanking(CommandContext<CommandSourceStack> ctx, String type, String category,
                                int page, List<RankingService.PlayerRankEntry> allPlayers, boolean isAll) {
        int totalPages = (int) Math.ceil((double) allPlayers.size() / ENTRIES_PER_PAGE);
        int startIndex = (page - 1) * ENTRIES_PER_PAGE;
        int endIndex = Math.min(startIndex + ENTRIES_PER_PAGE, allPlayers.size());

        List<RankingService.PlayerRankEntry> pageEntries = allPlayers.subList(startIndex, endIndex);
        Component fullDisplay = buildDisplay(type, isAll ? "all" : category, page, totalPages, pageEntries, startIndex);

        ctx.getSource().getSender().sendMessage(fullDisplay);
    }

    private Component buildDisplay(String type, String category, int page, int totalPages,
                                   List<RankingService.PlayerRankEntry> entries, int startRank) {
        String typeDisplay = plugin.getDisplayNamesManager().getTypeDisplayString(type);
        String categoryDisplay = plugin.getDisplayNamesManager().getCategoryDisplayString(category);
        String unit = plugin.getDisplayNamesManager().getUnitString(type);
        boolean isProgress = type.equalsIgnoreCase("progress");

        List<Component> entryComponents = new ArrayList<>();
        int rank = startRank + 1;
        for (RankingService.PlayerRankEntry entry : entries) {
            String formattedRank = String.format("%02d", rank);
            String scoreValue = isProgress
                    ? formatProgress(entry.getScore() / 10.0)
                    : messages.formatNumber(entry.getScore());

            String headTag = isBedrockPlayer(entry.getUuid())
                    ? "<head:" + entry.getName() + ">"
                    : "<head:" + entry.getUuid().toString() + ">";

            TagResolver allResolvers = TagResolver.resolver(
                    Placeholder.parsed("rank", formattedRank),
                    Placeholder.parsed("player", entry.getName()),
                    Placeholder.parsed("player_head", headTag),
                    Placeholder.parsed("score", scoreValue),
                    Placeholder.parsed("unit", unit)
            );
            entryComponents.add(messages.get("top.entry", allResolvers));
            rank++;
        }

        Component entriesBlock = Component.join(Component.newline(), entryComponents);
        Component previousButton = createPreviousButton(type, category, page);
        Component nextButton = createNextButton(type, category, page, totalPages);
        String formattedTotal = String.format("%02d", totalPages);
        String formattedPage = String.format("%02d", page);

        TagResolver displayResolvers = TagResolver.resolver(
                Placeholder.parsed("type", typeDisplay),
                Placeholder.parsed("category", categoryDisplay),
                Placeholder.parsed("current", formattedPage),
                Placeholder.parsed("total", formattedTotal),
                Placeholder.component("entries", entriesBlock),
                Placeholder.component("previous_page", previousButton),
                Placeholder.component("next_page", nextButton)
        );

        return messages.get("top.display", displayResolvers);
    }

    private boolean isBedrockPlayer(UUID uuid) {
        String uuidStr = uuid.toString();
        return uuidStr.startsWith("00000000-0000-0000");
    }

    private String formatProgress(double progress) {
        String formatted = String.format("%.1f", progress);
        return formatted.equals("100.0") ? "100" : formatted;
    }

    private Component createPreviousButton(String type, String category, int currentPage) {
        if (currentPage > 1) {
            int prevPage = currentPage - 1;
            String command = String.format("/pescastats top %s %s %d", type, category, prevPage);
            return messages.get("top.navigation.previous")
                    .clickEvent(ClickEvent.runCommand(command))
                    .hoverEvent(HoverEvent.showText(
                            messages.get("top.navigation.previous_hover",
                                    Placeholder.parsed("page", String.valueOf(prevPage)))
                    ));
        }
        return messages.get("top.navigation.previous_disabled");
    }

    private Component createNextButton(String type, String category, int currentPage, int totalPages) {
        if (currentPage < totalPages) {
            int nextPage = currentPage + 1;
            String command = String.format("/pescastats top %s %s %d", type, category, nextPage);
            return messages.get("top.navigation.next")
                    .clickEvent(ClickEvent.runCommand(command))
                    .hoverEvent(HoverEvent.showText(
                            messages.get("top.navigation.next_hover",
                                    Placeholder.parsed("page", String.valueOf(nextPage)))
                    ));
        }
        return messages.get("top.navigation.next_disabled");
    }

    private void handleError(CommandContext<CommandSourceStack> ctx, Exception e) {
        sendPrefixed(ctx.getSource().getSender(), "errors.unknown_error",
                Placeholder.parsed("error", e.getMessage()));
        plugin.getLogger().severe("Error in TopCommand: " + e.getMessage());
    }
}