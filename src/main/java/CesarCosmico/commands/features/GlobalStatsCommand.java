package CesarCosmico.commands.features;

import CesarCosmico.CustomFishingStats;
import CesarCosmico.commands.BaseCommand;
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
import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.storage.StorageManager;
import net.momirealms.customfishing.api.storage.data.StatisticData;
import net.momirealms.customfishing.api.mechanic.statistic.StatisticsManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("UnstableApiUsage")
public class GlobalStatsCommand extends BaseCommand {

    private static final int ENTRIES_PER_PAGE = 10;
    private final BukkitCustomFishingPlugin customFishing;
    private final List<String> categoryOrder;

    public GlobalStatsCommand(CustomFishingStats plugin) {
        super(plugin);
        this.customFishing = BukkitCustomFishingPlugin.getInstance();
        this.categoryOrder = loadCategoryOrder(plugin);
    }

    private List<String> loadCategoryOrder(CustomFishingStats plugin) {
        File displayNamesFile = new File(plugin.getDataFolder(), "display_names.yml");
        if (!displayNamesFile.exists()) {
            return new ArrayList<>();
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(displayNamesFile);
        ConfigurationSection categoriesSection = config.getConfigurationSection("categories");

        if (categoriesSection == null) {
            return new ArrayList<>();
        }

        return new ArrayList<>(categoriesSection.getKeys(false));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> create(CustomFishingStats plugin) {
        GlobalStatsCommand cmd = new GlobalStatsCommand(plugin);

        SuggestionProvider<CommandSourceStack> typeSuggestions = (context, builder) -> {
            builder.suggest("recycling");
            builder.suggest("recovery");
            builder.suggest("competition");
            builder.suggest("fishes");
            builder.suggest("items");
            return builder.buildFuture();
        };

        return LiteralArgumentBuilder.<CommandSourceStack>literal("global")
                .requires(source -> source.getSender().hasPermission("customfishingstats.top"))
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests(typeSuggestions)
                        .executes(ctx -> cmd.execute(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> cmd.execute(
                                        ctx,
                                        ctx.getArgument("page", Integer.class)
                                ))
                        )
                );
    }

    private int execute(CommandContext<CommandSourceStack> ctx, int page) {
        try {
            String type = ctx.getArgument("type", String.class).toLowerCase();

            return switch (type) {
                case "recycling", "recovery" -> executeSimple(ctx, type, page);
                case "competition" -> executeCompetition(ctx, page);
                case "fishes", "items" -> executeCustomFishing(ctx, type, page);
                default -> {
                    sendPrefixed(ctx.getSource().getSender(), "errors.invalid_type",
                            Placeholder.parsed("type", type));
                    yield 0;
                }
            };

        } catch (Exception e) {
            handleError(ctx, e);
            return 0;
        }
    }

    private int executeSimple(CommandContext<CommandSourceStack> ctx, String type, int page) {
        List<StatEntry> entries = new ArrayList<>();

        List<String> categories = plugin.getCategoriesByType(type);
        if (categories == null || categories.isEmpty()) {
            sendNoData(ctx, type);
            return 0;
        }

        Map<String, Integer> categoryValues = new HashMap<>();
        for (String category : categories) {
            int total = plugin.getCategoryTotal(type, category);
            if (total > 0) {
                categoryValues.put(category, total);
            }
        }

        for (String category : categoryOrder) {
            if (categoryValues.containsKey(category)) {
                String displayName = plugin.getDisplayNamesManager().getCategoryDisplayString(category);
                entries.add(new StatEntry(displayName, categoryValues.get(category)));
            }
        }

        for (Map.Entry<String, Integer> entry : categoryValues.entrySet()) {
            if (!categoryOrder.contains(entry.getKey())) {
                String displayName = plugin.getDisplayNamesManager().getCategoryDisplayString(entry.getKey());
                entries.add(new StatEntry(displayName, entry.getValue()));
            }
        }

        if (entries.isEmpty()) {
            sendNoData(ctx, type);
            return 0;
        }

        int grandTotal = entries.stream().mapToInt(StatEntry::value).sum();

        int totalPages = (int) Math.ceil((double) entries.size() / ENTRIES_PER_PAGE);

        if (page > totalPages || page < 1) {
            sendPrefixed(ctx.getSource().getSender(), "top.invalid_page",
                    Placeholder.parsed("page", String.valueOf(page)),
                    Placeholder.parsed("max_page", String.valueOf(totalPages)));
            return 0;
        }

        int startIndex = (page - 1) * ENTRIES_PER_PAGE;
        int endIndex = Math.min(startIndex + ENTRIES_PER_PAGE, entries.size());
        List<StatEntry> paginatedEntries = entries.subList(startIndex, endIndex);

        displayStats(ctx, type, page, paginatedEntries, grandTotal, totalPages);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeCompetition(CommandContext<CommandSourceStack> ctx, int page) {
        List<List<StatEntry>> sections = new ArrayList<>();
        List<String> sectionNames = new ArrayList<>();
        List<Integer> sectionTotals = new ArrayList<>();

        List<StatEntry> countSection = new ArrayList<>();
        List<String> countKeys = List.of("max_size_count", "min_size_count", "total_size_count", "infernal_count");
        for (String key : countKeys) {
            addCompetitionEntry(countSection, key);
        }
        if (!countSection.isEmpty()) {
            int countTotal = countSection.stream().mapToInt(StatEntry::value).sum();
            sections.add(countSection);
            sectionNames.add("count");
            sectionTotals.add(countTotal);
        }

        List<StatEntry> topsSection = new ArrayList<>();
        List<String> topsKeys = List.of("top_one", "top_two", "top_three");
        for (String key : topsKeys) {
            addCompetitionEntry(topsSection, key);
        }
        if (!topsSection.isEmpty()) {
            int topsTotal = topsSection.stream().mapToInt(StatEntry::value).sum();
            sections.add(topsSection);
            sectionNames.add("tops");
            sectionTotals.add(topsTotal);
        }

        List<StatEntry> participationSection = new ArrayList<>();
        List<String> participationKeys = List.of("participation_max_size", "participation_min_size",
                "participation_total_size", "participation_infernal");
        for (String key : participationKeys) {
            addCompetitionEntry(participationSection, key);
        }
        if (!participationSection.isEmpty()) {
            int participationTotal = participationSection.stream().mapToInt(StatEntry::value).sum();
            sections.add(participationSection);
            sectionNames.add("participation");
            sectionTotals.add(participationTotal);
        }

        int totalPages = sections.size();
        if (page > totalPages || page < 1) {
            sendPrefixed(ctx.getSource().getSender(), "top.invalid_page",
                    Placeholder.parsed("page", String.valueOf(page)),
                    Placeholder.parsed("max_page", String.valueOf(totalPages)));
            return 0;
        }

        List<StatEntry> selectedPage = sections.get(page - 1);
        int sectionTotal = sectionTotals.get(page - 1);
        String sectionName = sectionNames.get(page - 1);

        String unitType = "competition_" + sectionName;

        displayStatsWithUnit(ctx, "competition", page, selectedPage, sectionTotal, totalPages, unitType);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private void addCompetitionEntry(List<StatEntry> entries, String category) {
        int value = plugin.getCategoryTotal("competition", category);
        if (value > 0) {
            String displayName = plugin.getDisplayNamesManager().getCategoryDisplayString(category);
            entries.add(new StatEntry(displayName, value));
        }
    }

    private void displayStatsWithUnit(CommandContext<CommandSourceStack> ctx, String type,
                                      int page, List<StatEntry> entries, int grandTotal,
                                      int totalPages, String unitType) {
        Component fullDisplay = buildDisplayWithUnit(type, page, totalPages, entries, grandTotal, unitType);
        ctx.getSource().getSender().sendMessage(fullDisplay);
    }

    private Component buildDisplayWithUnit(String type, int page, int totalPages,
                                           List<StatEntry> entries, int grandTotal, String unitType) {
        String typeDisplay = plugin.getDisplayNamesManager().getTypeDisplayString(type);
        String formattedGrandTotal = messages.formatNumber(grandTotal);
        String unit = plugin.getDisplayNamesManager().getUnitString(unitType);

        List<Component> entryComponents = new ArrayList<>();
        for (StatEntry entry : entries) {
            String formattedValue = messages.formatNumber(entry.value());

            TagResolver resolver = TagResolver.resolver(
                    Placeholder.parsed("name", entry.name()),
                    Placeholder.parsed("value", formattedValue),
                    Placeholder.parsed("unit", unit)
            );
            entryComponents.add(messages.get("global.entry", resolver));
        }

        Component entriesBlock = Component.join(Component.newline(), entryComponents);
        Component previousButton = createPreviousButton(type, page);
        Component nextButton = createNextButton(type, page, totalPages);
        String formattedTotal = String.format("%02d", totalPages);
        String formattedPage = String.format("%02d", page);

        TagResolver displayResolvers = TagResolver.resolver(
                Placeholder.parsed("type", typeDisplay),
                Placeholder.parsed("total_value", formattedGrandTotal),
                Placeholder.parsed("unit", unit),
                Placeholder.parsed("current", formattedPage),
                Placeholder.parsed("total", formattedTotal),
                Placeholder.component("entries", entriesBlock),
                Placeholder.component("previous_page", previousButton),
                Placeholder.component("next_page", nextButton)
        );

        return messages.get("global.display", displayResolvers);
    }

    private void displayStats(CommandContext<CommandSourceStack> ctx, String type,
                              int page, List<StatEntry> allEntries, int grandTotal, int totalPages) {
        Component fullDisplay = buildDisplay(type, page, totalPages, allEntries, grandTotal);
        ctx.getSource().getSender().sendMessage(fullDisplay);
    }

    private Component buildDisplay(String type, int page, int totalPages,
                                   List<StatEntry> entries, int grandTotal) {
        String typeDisplay = plugin.getDisplayNamesManager().getTypeDisplayString(type);
        String formattedGrandTotal = messages.formatNumber(grandTotal);
        String unit = plugin.getDisplayNamesManager().getUnitString(type);

        List<Component> entryComponents = new ArrayList<>();
        for (StatEntry entry : entries) {
            String formattedValue = messages.formatNumber(entry.value());

            TagResolver resolver = TagResolver.resolver(
                    Placeholder.parsed("name", entry.name()),
                    Placeholder.parsed("value", formattedValue),
                    Placeholder.parsed("unit", unit)
            );
            entryComponents.add(messages.get("global.entry", resolver));
        }

        Component entriesBlock = Component.join(Component.newline(), entryComponents);
        Component previousButton = createPreviousButton(type, page);
        Component nextButton = createNextButton(type, page, totalPages);
        String formattedTotal = String.format("%02d", totalPages);
        String formattedPage = String.format("%02d", page);

        TagResolver displayResolvers = TagResolver.resolver(
                Placeholder.parsed("type", typeDisplay),
                Placeholder.parsed("total_value", formattedGrandTotal),
                Placeholder.parsed("unit", unit),
                Placeholder.parsed("current", formattedPage),
                Placeholder.parsed("total", formattedTotal),
                Placeholder.component("entries", entriesBlock),
                Placeholder.component("previous_page", previousButton),
                Placeholder.component("next_page", nextButton)
        );

        return messages.get("global.display", displayResolvers);
    }

    private int executeCustomFishing(CommandContext<CommandSourceStack> ctx, String type, int page) {
        sendPrefixed(ctx.getSource().getSender(), "global.calculating");

        Set<String> allCategories = plugin.getRankingService().getCustomFishingCategories();

        List<String> relevantCategories = allCategories.stream()
                .filter(cat -> {
                    if (type.equals("fishes")) {
                        return !cat.equalsIgnoreCase("fishes") && !cat.contains("item");
                    } else {
                        return !cat.equalsIgnoreCase("items") && cat.contains("item");
                    }
                })
                .toList();

        if (relevantCategories.isEmpty()) {
            sendNoData(ctx, type);
            return 0;
        }

        Map<String, Integer> categoryTotals = new HashMap<>();
        StorageManager storage = customFishing.getStorageManager();
        StatisticsManager statsManager = customFishing.getStatisticsManager();

        try {
            Set<UUID> uniqueUsers = storage.getDataSource().getUniqueUsers();

            for (String category : relevantCategories) {
                List<String> categoryItems = statsManager.getCategoryMembers(category);
                if (categoryItems == null || categoryItems.isEmpty()) continue;

                AtomicInteger globalTotal = new AtomicInteger(0);

                List<CompletableFuture<Void>> futures = uniqueUsers.stream()
                        .map(uuid -> storage.getDataSource().getPlayerData(uuid, false, Runnable::run)
                                .thenAccept(optPlayerData -> optPlayerData.ifPresent(playerData -> {
                                    StatisticData stats = playerData.statistics();
                                    if (stats != null && stats.amountMap != null) {
                                        for (String item : categoryItems) {
                                            globalTotal.addAndGet(stats.amountMap.getOrDefault(item, 0));
                                        }
                                    }
                                })))
                        .toList();

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                if (globalTotal.get() > 0) {
                    categoryTotals.put(category, globalTotal.get());
                }
            }

            String totalCategoryName = type.equals("fishes") ? "fishes" : "items";
            List<String> totalCategoryItems = statsManager.getCategoryMembers(totalCategoryName);

            int grandTotal = 0;
            if (totalCategoryItems != null && !totalCategoryItems.isEmpty()) {
                AtomicInteger total = new AtomicInteger(0);

                List<CompletableFuture<Void>> futures = uniqueUsers.stream()
                        .map(uuid -> storage.getDataSource().getPlayerData(uuid, false, Runnable::run)
                                .thenAccept(optPlayerData -> optPlayerData.ifPresent(playerData -> {
                                    StatisticData stats = playerData.statistics();
                                    if (stats != null && stats.amountMap != null) {
                                        for (String item : totalCategoryItems) {
                                            total.addAndGet(stats.amountMap.getOrDefault(item, 0));
                                        }
                                    }
                                })))
                        .toList();

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                grandTotal = total.get();
            }

            if (categoryTotals.isEmpty() && grandTotal == 0) {
                sendNoData(ctx, type);
                return 0;
            }

            List<StatEntry> entries = new ArrayList<>();

            for (String category : categoryOrder) {
                if (categoryTotals.containsKey(category)) {
                    String displayName = plugin.getDisplayNamesManager().getCategoryDisplayString(category);
                    entries.add(new StatEntry(displayName, categoryTotals.get(category)));
                }
            }

            for (Map.Entry<String, Integer> entry : categoryTotals.entrySet()) {
                if (!categoryOrder.contains(entry.getKey())) {
                    String displayName = plugin.getDisplayNamesManager().getCategoryDisplayString(entry.getKey());
                    entries.add(new StatEntry(displayName, entry.getValue()));
                }
            }

            int totalPages = (int) Math.ceil((double) entries.size() / ENTRIES_PER_PAGE);

            if (page > totalPages || page < 1) {
                sendPrefixed(ctx.getSource().getSender(), "top.invalid_page",
                        Placeholder.parsed("page", String.valueOf(page)),
                        Placeholder.parsed("max_page", String.valueOf(totalPages)));
                return 0;
            }

            int startIndex = (page - 1) * ENTRIES_PER_PAGE;
            int endIndex = Math.min(startIndex + ENTRIES_PER_PAGE, entries.size());
            List<StatEntry> paginatedEntries = entries.subList(startIndex, endIndex);

            displayStats(ctx, type, page, paginatedEntries, grandTotal, totalPages);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;

        } catch (Exception e) {
            plugin.getLogger().severe("Error getting CustomFishing global stats: " + e.getMessage());
            e.printStackTrace();
            sendNoData(ctx, type);
            return 0;
        }
    }

    private Component createPreviousButton(String type, int currentPage) {
        if (currentPage > 1) {
            int prevPage = currentPage - 1;
            String command = String.format("/pescastats global %s %d", type, prevPage);
            return messages.get("top.navigation.previous")
                    .clickEvent(ClickEvent.runCommand(command))
                    .hoverEvent(HoverEvent.showText(
                            messages.get("top.navigation.previous_hover",
                                    Placeholder.parsed("page", String.valueOf(prevPage)))
                    ));
        }
        return messages.get("top.navigation.previous_disabled");
    }

    private Component createNextButton(String type, int currentPage, int totalPages) {
        if (currentPage < totalPages) {
            int nextPage = currentPage + 1;
            String command = String.format("/pescastats global %s %d", type, nextPage);
            return messages.get("top.navigation.next")
                    .clickEvent(ClickEvent.runCommand(command))
                    .hoverEvent(HoverEvent.showText(
                            messages.get("top.navigation.next_hover",
                                    Placeholder.parsed("page", String.valueOf(nextPage)))
                    ));
        }
        return messages.get("top.navigation.next_disabled");
    }

    private void sendNoData(CommandContext<CommandSourceStack> ctx, String type) {
        sendPrefixed(ctx.getSource().getSender(), "global.no_data",
                Placeholder.parsed("type", type));
    }

    private void handleError(CommandContext<CommandSourceStack> ctx, Exception e) {
        sendPrefixed(ctx.getSource().getSender(), "errors.unknown_error",
                Placeholder.parsed("error", e.getMessage()));
        plugin.getLogger().severe("Error in GlobalStatsCommand: " + e.getMessage());
        e.printStackTrace();
    }

    private record StatEntry(String name, int value) {}
}