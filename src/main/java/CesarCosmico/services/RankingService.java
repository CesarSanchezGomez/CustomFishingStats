package CesarCosmico.services;

import CesarCosmico.storage.StorageManager;
import CesarCosmico.storage.data.PlayerData;
import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.storage.user.UserData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class RankingService {

    private final StorageManager storageManager;
    private final File dataFolder;
    private final BukkitCustomFishingPlugin customFishing;
    private final Map<String, Set<String>> customFishingCategoriesMap;

    private final Map<String, CachedRanking> rankingCache = new ConcurrentHashMap<>();
    private final Map<String, CachedProgressRanking> progressRankingCache = new ConcurrentHashMap<>();
    private final Set<String> calculatingKeys = ConcurrentHashMap.newKeySet();
    private volatile boolean isCalculating = false;

    public static class PlayerRankEntry {
        private final UUID uuid;
        private final String name;
        private final int score;

        public PlayerRankEntry(UUID uuid, String name, int score) {
            this.uuid = uuid;
            this.name = name;
            this.score = score;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public int getScore() {
            return score;
        }
    }

    public static class PlayerProgressEntry {
        private final UUID uuid;
        private final String name;
        private final double progress;

        public PlayerProgressEntry(UUID uuid, String name, double progress) {
            this.uuid = uuid;
            this.name = name;
            this.progress = progress;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public double getProgress() {
            return progress;
        }
    }

    public RankingService(StorageManager storageManager, File dataFolder,
                          Map<String, Set<String>> customFishingCategoriesMap) {
        this.storageManager = storageManager;
        this.dataFolder = new File(dataFolder, "storage/data");
        this.customFishing = BukkitCustomFishingPlugin.getInstance();
        this.customFishingCategoriesMap = customFishingCategoriesMap != null
                ? Map.copyOf(customFishingCategoriesMap) : Map.of();
    }

    public List<PlayerRankEntry> getTopPlayersWithUUID(String type, String category, int limit) {
        String cacheKey = type + ":" + category;

        if (!isCustomFishingType(type)) {
            CachedRanking cached = rankingCache.get(cacheKey);

            if (cached != null && !cached.isExpired()) {
                return cached.getTop(limit);
            }

            if (calculatingKeys.contains(cacheKey)) {
                if (cached != null) {
                    return cached.getTop(limit);
                }
                return Collections.emptyList();
            }
        }

        return calculateTopPlayers(type, category, limit);
    }

    public List<Map.Entry<String, Integer>> getTopPlayers(String type, String category, int limit) {
        return getTopPlayersWithUUID(type, category, limit).stream()
                .map(entry -> Map.entry(entry.getName(), entry.getScore()))
                .collect(java.util.stream.Collectors.toList());
    }

    public List<PlayerRankEntry> getTopPlayersByTypeWithUUID(String type, int limit) {
        String cacheKey = type + ":__ALL__";

        if (!isCustomFishingType(type)) {
            CachedRanking cached = rankingCache.get(cacheKey);

            if (cached != null && !cached.isExpired()) {
                return cached.getTop(limit);
            }

            if (calculatingKeys.contains(cacheKey)) {
                if (cached != null) {
                    return cached.getTop(limit);
                }
                return Collections.emptyList();
            }
        }

        return calculateTopPlayersByType(type, limit);
    }

    public List<Map.Entry<String, Integer>> getTopPlayersByType(String type, int limit) {
        return getTopPlayersByTypeWithUUID(type, limit).stream()
                .map(entry -> Map.entry(entry.getName(), entry.getScore()))
                .collect(java.util.stream.Collectors.toList());
    }

    public List<PlayerProgressEntry> getTopPlayersByProgressWithUUID(String category, int limit) {
        String cacheKey = "progress:" + category;

        if (calculatingKeys.contains(cacheKey)) {
            CachedProgressRanking cached = progressRankingCache.get(cacheKey);
            if (cached != null) {
                return cached.getTop(limit);
            }
            return Collections.emptyList();
        }

        return calculateTopPlayersByProgress(category, limit);
    }

    public List<Map.Entry<String, Double>> getTopPlayersByProgress(String category, int limit) {
        return getTopPlayersByProgressWithUUID(category, limit).stream()
                .map(entry -> Map.entry(entry.getName(), entry.getProgress()))
                .collect(java.util.stream.Collectors.toList());
    }

    public int getPlayerRank(UUID targetUuid, String type, String category) {
        String cacheKey = type + ":" + category;

        if (!isCustomFishingType(type)) {
            CachedRanking cached = rankingCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                return cached.getRank(getPlayerName(targetUuid));
            }
        }

        calculateTopPlayers(type, category, 1000);
        CachedRanking cached = rankingCache.get(cacheKey);
        return cached != null ? cached.getRank(getPlayerName(targetUuid)) : 0;
    }

    public void invalidatePlayerCache(UUID uuid) {
        String playerName = getPlayerName(uuid);
        if (playerName == null) return;

        Iterator<Map.Entry<String, CachedRanking>> iterator = rankingCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedRanking> entry = iterator.next();
            CachedRanking cached = entry.getValue();

            if (cached.containsPlayer(playerName)) {
                iterator.remove();
                continue;
            }

            String cacheKey = entry.getKey();
            String type = cacheKey.contains(":") ? cacheKey.substring(0, cacheKey.indexOf(':')) : null;
            if (type != null && !type.equals("progress")) {
                String allKey = type + ":__ALL__";
                if (rankingCache.containsKey(allKey)) {
                    rankingCache.remove(allKey);
                }
            }
        }
    }

    public void invalidateCategoryCache(String type, String category) {
        String cacheKey = type + ":" + category;
        rankingCache.remove(cacheKey);

        String typeKey = type + ":__ALL__";
        rankingCache.remove(typeKey);
    }

    public void recalculateAll() {
        if (isCalculating) return;

        Bukkit.getScheduler().runTaskAsynchronously(
                Bukkit.getPluginManager().getPlugin("CustomFishingStats"),
                () -> {
                    isCalculating = true;
                    rankingCache.clear();

                    for (String type : customFishingCategoriesMap.keySet()) {
                        calculateTopPlayersByType(type, 1000);

                        Set<String> categories = customFishingCategoriesMap.get(type);
                        if (categories != null) {
                            for (String category : categories) {
                                calculateTopPlayers(type, category, 1000);
                            }
                        }
                    }

                    storageManager.clearOfflineCache();
                    isCalculating = false;
                }
        );
    }

    public void clearCache() {
        rankingCache.clear();
        progressRankingCache.clear();
    }

    public boolean isValidCustomFishingCategory(String category) {
        if (customFishingCategoriesMap == null || customFishingCategoriesMap.isEmpty()) {
            return false;
        }

        for (Set<String> categories : customFishingCategoriesMap.values()) {
            if (categories.contains(category)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getCustomFishingCategories() {
        Set<String> allCategories = new HashSet<>();
        if (customFishingCategoriesMap != null) {
            for (Set<String> categories : customFishingCategoriesMap.values()) {
                allCategories.addAll(categories);
            }
        }
        return allCategories;
    }

    private List<PlayerRankEntry> calculateTopPlayers(String type, String category, int limit) {
        String cacheKey = type + ":" + category;

        if (!calculatingKeys.add(cacheKey)) {
            CachedRanking cached = rankingCache.get(cacheKey);
            if (cached != null) {
                return cached.getTop(limit);
            }
            return Collections.emptyList();
        }

        try {
            Map<UUID, PlayerRankEntry> playerScores = new ConcurrentHashMap<>();
            Set<UUID> allPlayerUUIDs = getAllPlayerUUIDs();

            int batchSize = 100;
            List<UUID> uuidList = new ArrayList<>(allPlayerUUIDs);
            List<CompletableFuture<Void>> batchFutures = new ArrayList<>();

            for (int i = 0; i < uuidList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, uuidList.size());
                List<UUID> batch = uuidList.subList(i, end);

                CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
                    for (UUID uuid : batch) {
                        try {
                            String playerName = getPlayerName(uuid);
                            if (playerName == null || playerName.isEmpty()) continue;

                            int score = getPlayerScore(uuid, type, category);
                            if (score > 0) {
                                playerScores.put(uuid, new PlayerRankEntry(uuid, playerName, score));
                            }
                        } catch (Exception e) {
                            // Skip
                        }
                    }
                });
                batchFutures.add(batchFuture);
            }

            CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();

            List<PlayerRankEntry> sorted = playerScores.values().stream()
                    .sorted(Comparator.comparingInt(PlayerRankEntry::getScore).reversed())
                    .collect(java.util.stream.Collectors.toList());

            rankingCache.put(cacheKey, new CachedRanking(sorted));

            return sorted.stream().limit(limit).collect(java.util.stream.Collectors.toList());
        } finally {
            calculatingKeys.remove(cacheKey);
        }
    }

    private List<PlayerRankEntry> calculateTopPlayersByType(String type, int limit) {
        String cacheKey = type + ":__ALL__";

        if (!calculatingKeys.add(cacheKey)) {
            CachedRanking cached = rankingCache.get(cacheKey);
            if (cached != null) {
                return cached.getTop(limit);
            }
            return Collections.emptyList();
        }

        try {
            Map<UUID, PlayerRankEntry> playerScores = new ConcurrentHashMap<>();
            Set<UUID> allPlayerUUIDs = getAllPlayerUUIDs();

            int batchSize = 100;
            List<UUID> uuidList = new ArrayList<>(allPlayerUUIDs);
            List<CompletableFuture<Void>> batchFutures = new ArrayList<>();

            for (int i = 0; i < uuidList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, uuidList.size());
                List<UUID> batch = uuidList.subList(i, end);

                CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
                    for (UUID uuid : batch) {
                        try {
                            String playerName = getPlayerName(uuid);
                            if (playerName == null || playerName.isEmpty()) continue;

                            int score = isCustomFishingType(type)
                                    ? getCustomFishingTotal(uuid)
                                    : getCustomTotalByType(uuid, type);

                            if (score > 0) {
                                playerScores.put(uuid, new PlayerRankEntry(uuid, playerName, score));
                            }
                        } catch (Exception e) {
                            // Skip
                        }
                    }
                });
                batchFutures.add(batchFuture);
            }

            CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();

            List<PlayerRankEntry> sorted = playerScores.values().stream()
                    .sorted(Comparator.comparingInt(PlayerRankEntry::getScore).reversed())
                    .collect(java.util.stream.Collectors.toList());

            rankingCache.put(cacheKey, new CachedRanking(sorted));

            return sorted.stream().limit(limit).collect(java.util.stream.Collectors.toList());
        } finally {
            calculatingKeys.remove(cacheKey);
        }
    }

    private List<PlayerProgressEntry> calculateTopPlayersByProgress(String category, int limit) {
        String cacheKey = "progress:" + category;

        if (!calculatingKeys.add(cacheKey)) {
            CachedProgressRanking cached = progressRankingCache.get(cacheKey);
            if (cached != null) {
                return cached.getTop(limit);
            }
            return Collections.emptyList();
        }

        try {
            Map<UUID, PlayerProgressEntry> playerProgress = new ConcurrentHashMap<>();
            Set<UUID> allPlayerUUIDs = getAllPlayerUUIDs();

            List<String> categoryMembers = customFishing.getStatisticsManager()
                    .getCategoryMembers(category);

            if (categoryMembers == null || categoryMembers.isEmpty()) {
                return Collections.emptyList();
            }

            int totalItems = categoryMembers.size();

            int batchSize = 100;
            List<UUID> uuidList = new ArrayList<>(allPlayerUUIDs);
            List<CompletableFuture<Void>> batchFutures = new ArrayList<>();

            for (int i = 0; i < uuidList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, uuidList.size());
                List<UUID> batch = uuidList.subList(i, end);

                CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
                    for (UUID uuid : batch) {
                        try {
                            String playerName = getPlayerName(uuid);
                            if (playerName == null || playerName.isEmpty()) continue;

                            double progress = calculatePlayerProgress(uuid, categoryMembers, totalItems);
                            if (progress > 0) {
                                playerProgress.put(uuid, new PlayerProgressEntry(uuid, playerName, progress));
                            }
                        } catch (Exception e) {
                            // Skip
                        }
                    }
                });
                batchFutures.add(batchFuture);
            }

            CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();

            List<PlayerProgressEntry> sorted = playerProgress.values().stream()
                    .sorted(Comparator.comparingDouble(PlayerProgressEntry::getProgress).reversed())
                    .collect(java.util.stream.Collectors.toList());

            progressRankingCache.put(cacheKey, new CachedProgressRanking(sorted));

            return sorted.stream().limit(limit).collect(java.util.stream.Collectors.toList());
        } finally {
            calculatingKeys.remove(cacheKey);
        }
    }

    private double calculatePlayerProgress(UUID uuid, List<String> categoryMembers, int totalItems) {
        try {
            UserData userData = getUserData(uuid);
            if (userData == null) return 0.0;

            var stats = userData.statistics();
            if (stats == null) return 0.0;

            int unlockedItems = 0;
            for (String itemId : categoryMembers) {
                if (stats.getAmount(itemId) > 0) {
                    unlockedItems++;
                }
            }

            return ((double) unlockedItems * 100) / totalItems;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private boolean isCustomFishingType(String type) {
        return type.equals("progress") || type.equals("category");
    }

    private int getPlayerScore(UUID uuid, String type, String category) {
        if (isCustomFishingType(type)) {
            return getCustomFishingScore(uuid, category);
        }

        PlayerData cached = storageManager.getOnlinePlayerSnapshot(uuid);
        if (cached == null) {
            cached = storageManager.getOfflineCachedSnapshot(uuid);
        }

        if (cached != null) {
            return cached.getCategoryTotal(type, category);
        }

        return getCustomScoreFromDisk(uuid, type, category);
    }

    private int getCustomScoreFromDisk(UUID uuid, String type, String category) {
        try {
            PlayerData playerData = storageManager.loadPlayerData(uuid).join();
            if (playerData == null) return 0;
            return playerData.getCategoryTotal(type, category);
        } catch (Exception e) {
            return 0;
        }
    }

    private int getCustomTotalByType(UUID uuid, String type) {
        PlayerData cached = storageManager.getOnlinePlayerSnapshot(uuid);
        if (cached == null) {
            cached = storageManager.getOfflineCachedSnapshot(uuid);
        }

        if (cached != null) {
            return cached.getTotalByType(type);
        }

        try {
            PlayerData playerData = storageManager.loadPlayerData(uuid).join();
            if (playerData == null) return 0;
            return playerData.getTotalByType(type);
        } catch (Exception e) {
            return 0;
        }
    }

    private int getCustomFishingTotal(UUID uuid) {
        try {
            Optional<UserData> online = customFishing.getStorageManager().getOnlineUser(uuid);
            if (online.isPresent()) {
                var stats = online.get().statistics();
                return stats != null ? stats.amountOfFishCaught() : 0;
            }

            CompletableFuture<Optional<net.momirealms.customfishing.api.storage.data.PlayerData>> future =
                    customFishing.getStorageManager().getDataSource().getPlayerData(uuid, false, Runnable::run);
            Optional<net.momirealms.customfishing.api.storage.data.PlayerData> optional = future.get();

            if (optional.isEmpty() || optional.get().statistics() == null) {
                return 0;
            }

            int total = 0;
            for (int value : optional.get().statistics().amountMap.values()) {
                total += value;
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    private int getCustomFishingScore(UUID uuid, String category) {
        try {
            UserData userData = getUserData(uuid);
            if (userData == null) return 0;

            net.momirealms.customfishing.api.mechanic.statistic.FishingStatistics stats = userData.statistics();
            if (stats == null) return 0;

            List<String> categoryMembers = customFishing.getStatisticsManager().getCategoryMembers(category);
            if (categoryMembers != null && !categoryMembers.isEmpty()) {
                int total = 0;
                for (String itemId : categoryMembers) {
                    total += stats.getAmount(itemId);
                }
                return total;
            }

            return stats.getAmount(category);
        } catch (Exception e) {
            return 0;
        }
    }

    private UserData getUserData(UUID uuid) {
        try {
            Optional<UserData> onlineUser = customFishing.getStorageManager().getOnlineUser(uuid);
            if (onlineUser.isPresent()) {
                return onlineUser.get();
            }

            CompletableFuture<Optional<UserData>> future =
                    customFishing.getStorageManager().getOfflineUserData(uuid, false);
            Optional<UserData> offlineUser = future.join();
            return offlineUser.orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Set<UUID> getAllPlayerUUIDs() {
        Set<UUID> allUUIDs = ConcurrentHashMap.newKeySet();

        if (dataFolder.exists()) {
            File[] playerFiles = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (playerFiles != null) {
                for (File file : playerFiles) {
                    try {
                        UUID uuid = UUID.fromString(file.getName().replace(".yml", ""));
                        allUUIDs.add(uuid);
                    } catch (Exception e) {
                        // Skip invalid files
                    }
                }
            }
        }

        try {
            Collection<UserData> onlineUsers = customFishing.getStorageManager().getOnlineUsers();
            for (UserData user : onlineUsers) {
                allUUIDs.add(user.uuid());
            }
        } catch (Exception e) {
            // Skip
        }

        return allUUIDs;
    }

    private String getPlayerName(UUID uuid) {
        try {
            PlayerData cached = storageManager.getOnlinePlayerSnapshot(uuid);
            if (cached == null) {
                cached = storageManager.getOfflineCachedSnapshot(uuid);
            }

            if (cached != null && cached.getName() != null && !cached.getName().isEmpty()) {
                return cached.getName();
            }

            PlayerData playerData = storageManager.loadPlayerData(uuid).join();
            if (playerData != null && playerData.getName() != null && !playerData.getName().isEmpty()) {
                return playerData.getName();
            }

            UserData userData = getUserData(uuid);
            if (userData != null && userData.name() != null && !userData.name().isEmpty()) {
                return userData.name();
            }

            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String name = offlinePlayer.getName();

            if (name != null && !name.equals(uuid.toString())) {
                return name;
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static class CachedRanking {
        private final List<PlayerRankEntry> ranking;
        private final Set<String> playerSet;
        private final long timestamp;
        private static final long CACHE_DURATION = 5 * 60 * 1000;

        public CachedRanking(List<PlayerRankEntry> ranking) {
            this.ranking = ranking;
            this.playerSet = new HashSet<>();
            for (PlayerRankEntry entry : ranking) {
                playerSet.add(entry.getName());
            }
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_DURATION;
        }

        public List<PlayerRankEntry> getTop(int limit) {
            return ranking.stream().limit(limit).collect(java.util.stream.Collectors.toList());
        }

        public int getRank(String playerName) {
            if (playerName == null) return 0;
            for (int i = 0; i < ranking.size(); i++) {
                if (ranking.get(i).getName().equals(playerName)) {
                    return i + 1;
                }
            }
            return 0;
        }

        public boolean containsPlayer(String playerName) {
            return playerSet.contains(playerName);
        }
    }

    private static class CachedProgressRanking {
        private final List<PlayerProgressEntry> ranking;
        private final Set<String> playerSet;
        private final long timestamp;
            private static final long CACHE_DURATION = 5 * 60 * 1000;

        public CachedProgressRanking(List<PlayerProgressEntry> ranking) {
            this.ranking = ranking;
            this.playerSet = new HashSet<>();
            for (PlayerProgressEntry entry : ranking) {
                playerSet.add(entry.getName());
            }
            this.timestamp = System.currentTimeMillis();
        }

        public List<PlayerProgressEntry> getTop(int limit) {
            return ranking.stream().limit(limit).collect(java.util.stream.Collectors.toList());
        }
    }
}