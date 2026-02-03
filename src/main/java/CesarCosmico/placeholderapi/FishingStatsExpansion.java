package CesarCosmico.placeholderapi;

import CesarCosmico.CustomFishingStats;
import CesarCosmico.storage.data.PlayerData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class FishingStatsExpansion extends PlaceholderExpansion {

    private final CustomFishingStats plugin;
    private final PlaceholderParser parser;

    public FishingStatsExpansion(CustomFishingStats plugin) {
        this.plugin = plugin;
        this.parser = new PlaceholderParser();
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "cfs";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return "CesarCosmico";
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    @Nullable
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        PlaceholderRequest request = parser.parse(params);
        if (request == null) return null;

        return switch (request.getMainCategory()) {
            case "player" -> handlePlayer(player, request);
            case "global" -> handleGlobal(request);
            case "top" -> handleTop(request);
            case "rank" -> handleRank(player, request);
            default -> null;
        };
    }

    private String handlePlayer(OfflinePlayer player, PlaceholderRequest request) {
        if (player == null) return "0";

        PlayerData data = loadPlayerData(player);
        if (data == null) return "0";

        String[] args = request.getArgs();

        if (args.length == 2 && args[1].equals("types")) {
            return String.valueOf(data.getAllTypes().size());
        }

        if (args.length == 3 && args[1].equals("total")) {
            return String.valueOf(data.getTotalByType(args[2]));
        }

        if (args.length >= 5 && args[1].equals("category")) {
            return handlePlayerCategory(data, args);
        }

        return null;
    }

    private String handlePlayerCategory(PlayerData data, String[] args) {
        String action = args[2];
        String type = args[3];

        if (action.equals("total") && args.length >= 5) {
            String category = join(args, 4, args.length);
            return String.valueOf(data.getCategoryTotal(type, category));
        }

        if (action.equals("item") && args.length >= 6) {

            String categoryOpt1 = join(args, 4, args.length - 1);
            String itemOpt1 = args[args.length - 1];
            int amountOpt1 = data.getItemAmount(type, categoryOpt1, itemOpt1);

            if (amountOpt1 > 0) {
                return String.valueOf(amountOpt1);
            }

            String fullKey = join(args, 4, args.length);
            int amountOpt2 = data.getItemAmount(type, "", fullKey);

            if (amountOpt2 > 0) {
                return String.valueOf(amountOpt2);
            }

            if (args.length >= 7) {
                String categoryOpt3 = join(args, 4, 6);
                String itemOpt3 = join(args, 6, args.length);
                int amountOpt3 = data.getItemAmount(type, categoryOpt3, itemOpt3);

                if (amountOpt3 > 0) {
                    return String.valueOf(amountOpt3);
                }
            }

            return "0";
        }

        return null;
    }

    private String handleGlobal(PlaceholderRequest request) {
        String[] args = request.getArgs();

        switch (args[1]) {
            case "types":
                return String.valueOf(plugin.getAllTypes().size());

            case "total":
                if (args.length != 3) return null;
                return String.valueOf(plugin.getGlobalTotal(args[2]));

            case "category":
                if (args.length < 5) return null;
                return handleGlobalCategory(args);
        }

        return null;
    }

    private String handleGlobalCategory(String[] args) {
        String action = args[2];
        String type = args[3];

        if (action.equals("total")) {
            String category = join(args, 4, args.length);
            return String.valueOf(plugin.getCategoryTotal(type, category));
        }

        if (action.equals("item") && args.length >= 6) {
            String category = join(args, 4, args.length - 1);
            String item = args[args.length - 1];

            Map<String, Integer> items = plugin.getCategoryItems(type, category);
            return String.valueOf(items.getOrDefault(item, 0));
        }

        return null;
    }

    private String handleTop(PlaceholderRequest request) {
        String[] args = request.getArgs();

        if (args.length < 7) return null;
        if (!args[1].equals("category")) return null;

        String field = args[2];
        String type = args[3];

        int position;
        try {
            position = Integer.parseInt(args[args.length - 1]);
        } catch (NumberFormatException e) {
            return null;
        }

        String category = join(args, 4, args.length - 1);
        var top = plugin.getTopPlayers(type, category, position);

        if (position < 1 || position > top.size()) {
            return field.equals("name") ? "---" : "0";
        }

        var entry = top.get(position - 1);
        return field.equals("name")
                ? entry.getKey()
                : String.valueOf(entry.getValue());
    }

    private String handleRank(OfflinePlayer player, PlaceholderRequest request) {
        if (player == null) return "N/A";

        String[] args = request.getArgs();
        if (args.length < 5) return "N/A";
        if (!args[1].equals("category")) return null;

        String type = args[2];
        String category = join(args, 3, args.length);

        int rank = plugin.getPlayerRank(player.getUniqueId(), type, category);
        return rank > 0 ? String.valueOf(rank) : "N/A";
    }

    private PlayerData loadPlayerData(OfflinePlayer player) {
        try {
            CompletableFuture<PlayerData> future =
                    plugin.getStorageManager().loadPlayerData(player.getUniqueId());
            return future.get();
        } catch (Exception e) {
            return null;
        }
    }

    private String join(String[] array, int from, int to) {
        return String.join("_", java.util.Arrays.copyOfRange(array, from, to));
    }

    private static class PlaceholderParser {
        public PlaceholderRequest parse(String params) {
            String[] args = params.toLowerCase().split("_");
            if (args.length < 2) return null;

            return new PlaceholderRequest(args[0], args);
        }
    }

    private static class PlaceholderRequest {
        private final String mainCategory;
        private final String[] args;

        public PlaceholderRequest(String mainCategory, String[] args) {
            this.mainCategory = mainCategory;
            this.args = args;
        }

        public String getMainCategory() {
            return mainCategory;
        }

        public String[] getArgs() {
            return args;
        }
    }
}
