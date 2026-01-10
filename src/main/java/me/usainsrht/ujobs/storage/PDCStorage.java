package me.usainsrht.ujobs.storage;

import lombok.Getter;
import me.usainsrht.ujobs.UJobsPlugin;
import me.usainsrht.ujobs.models.PlayerJobData;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Getter
public class PDCStorage implements Storage {

    UJobsPlugin plugin;
    HashMap<UUID, PlayerJobData> cache;

    public static final NamespacedKey TAG_JOBS_DATA = new NamespacedKey("ujobs", "jobs_data");

    public static final NamespacedKey TAG_LEVEL = new NamespacedKey("ujobs", "level");
    public static final NamespacedKey TAG_EXP = new NamespacedKey("ujobs", "exp");
    public static final NamespacedKey TAG_TOTAL_MONEY = new NamespacedKey("ujobs", "total_money");

    public PDCStorage(UJobsPlugin plugin) {
        this.plugin = plugin;
        cache = new HashMap<>();
    }

    public PersistentDataContainer serialize(PersistentDataAdapterContext context, PlayerJobData playerJobData) {
        PersistentDataContainer container = context.newPersistentDataContainer();

        for (Map.Entry<String, PlayerJobData.JobStats> entry : playerJobData.getJobStats().entrySet()) {
            PlayerJobData.JobStats jobStats = entry.getValue();
            if (jobStats.getExp() == 0 && jobStats.getLevel() == 0 && jobStats.getTotalMoney() == 0) {
                continue; // skip empty job stats
            }
            NamespacedKey jobKey = NamespacedKey.fromString(entry.getKey(), plugin);
            PersistentDataContainer jobContainer = serialize(context, jobStats);
            container.set(jobKey, PersistentDataType.TAG_CONTAINER, jobContainer);
        }

        return container;
    }

    public PersistentDataContainer serialize(PersistentDataAdapterContext context, PlayerJobData.JobStats jobStats) {
        PersistentDataContainer container = context.newPersistentDataContainer();

        container.set(TAG_LEVEL, PersistentDataType.INTEGER, jobStats.getLevel());
        container.set(TAG_EXP, PersistentDataType.DOUBLE, jobStats.getExp());
        container.set(TAG_TOTAL_MONEY, PersistentDataType.DOUBLE, jobStats.getTotalMoney());

        return container;
    }

    public PlayerJobData deserialize(UUID uuid, PersistentDataContainer pdc) {
        PlayerJobData playerJobData = new PlayerJobData(uuid);

        for (NamespacedKey jobKey : pdc.getKeys()) {
            if (!pdc.has(jobKey, PersistentDataType.TAG_CONTAINER)) {
                continue; // skip if not a job container
            }
            PersistentDataContainer jobContainer = pdc.get(jobKey, PersistentDataType.TAG_CONTAINER);
            PlayerJobData.JobStats jobStats = deserializeJobStats(jobContainer);
            playerJobData.setJobStats(jobKey.getKey(), jobStats);
        }

        return playerJobData;

    }

    public PlayerJobData.JobStats deserializeJobStats(PersistentDataContainer jobContainer) {
        int level = jobContainer.get(TAG_LEVEL, PersistentDataType.INTEGER);
        double exp = jobContainer.get(TAG_EXP, PersistentDataType.DOUBLE);
        double totalMoney = jobContainer.get(TAG_TOTAL_MONEY, PersistentDataType.DOUBLE);

        return new PlayerJobData.JobStats(level, exp, totalMoney);
    }

    /**
     * Parse a textual NBT-inner block (the contents of the ujobs compound) into PlayerJobData.
     * This mirrors the logic used when deserializing from a live PersistentDataContainer.
     */
    public PlayerJobData parseFromText(UUID uuid, String inner) {
        PlayerJobData result = new PlayerJobData(uuid);
        java.util.regex.Pattern anyJobPattern = java.util.regex.Pattern.compile("([a-zA-Z0-9_:\\-]+)\\s*[:=]\\s*\\{([^}]*)\\}", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher anyMatcher = anyJobPattern.matcher(inner);
        while (anyMatcher.find()) {
            String rawKey = anyMatcher.group(1);
            String jobKey = rawKey.startsWith("ujobs:") ? rawKey.substring("ujobs:".length()) : rawKey;
            String jobBody = anyMatcher.group(2);
            java.util.regex.Matcher levelM = java.util.regex.Pattern.compile("level\\s*[:=]\\s*([0-9]+)").matcher(jobBody);
            java.util.regex.Matcher expM = java.util.regex.Pattern.compile("(?:exp|xp)\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(jobBody);
            int level = -1;
            double exp = 0.0;
            if (levelM.find()) level = Integer.parseInt(levelM.group(1));
            if (expM.find()) exp = Double.parseDouble(expM.group(1));

            // If level missing but exp present, compute level via job config
            me.usainsrht.ujobs.models.Job job = plugin.getJobManager().getJobs().get(jobKey);
            if ((level < 0 || level == 0) && exp > 0 && job != null) {
                int computedLevel = 0;
                double accumulated = 0.0;
                while (true) {
                    double need = job.calculateExpForLevel(computedLevel);
                    if (accumulated + need > exp) break;
                    accumulated += need;
                    computedLevel++;
                }
                double expForCurrent = exp - accumulated;
                level = computedLevel;
                exp = expForCurrent;
            } else if (level < 0) {
                level = 0;
            }

            result.setJobStats(jobKey, new PlayerJobData.JobStats(level, exp, 0.0));
        }
        return result;
    }

    @Override
    public void save() {
        //plugin.getLogger().info("Saving cache job data to PDC storage.");
        for (UUID uuid : cache.keySet()) {
            save(uuid);
        }
        //plugin.getLogger().info("Saved cache job data to PDC storage.");
    }

    @Override
    public void save(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            PlayerJobData playerJobData = getCached(uuid);
            if (playerJobData == null) return;

            set(player, playerJobData);
        } else {
            //todo implement offlineplayer data save
            //currently not needed
        }
    }

    @Override
    public CompletableFuture<PlayerJobData> load(UUID uuid) {
        CompletableFuture<PlayerJobData> future = new CompletableFuture<>();

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            plugin.getLogger().info("PDCStorage.load: starting async load for " + uuid);
            player.getScheduler().run(plugin, task -> {
                plugin.getLogger().info("PDCStorage.load: async task running for " + uuid);
                //success
                PersistentDataContainer pdc = player.getPersistentDataContainer();
                PlayerJobData playerJobData;
                if (pdc.has(TAG_JOBS_DATA)) {
                    // If the player has PDC data, deserialize and use it (authoritative).
                    plugin.getLogger().info("PDCStorage.load: found PDC data for " + uuid);
                    PlayerJobData deserialized = deserialize(uuid, pdc.get(TAG_JOBS_DATA, PersistentDataType.TAG_CONTAINER));
                    
                    // Merge with YAML cache: if YAML has jobs missing from deserialized PDC, add them.
                    // This handles cases where PDC has partial/old data and YAML has updated levels.
                    PlayerJobData existing = cache.get(uuid);
                    if (existing != null) {
                        for (Map.Entry<String, PlayerJobData.JobStats> yamlEntry : existing.getJobStats().entrySet()) {
                            String jobId = yamlEntry.getKey();
                            // Always use YAML version if available (YAML is authoritative from leaderboard.yml)
                            deserialized.setJobStats(jobId, yamlEntry.getValue());
                        }
                        plugin.getLogger().info("PDCStorage.load: merged all jobs from YAML for " + uuid);
                    }
                    
                    playerJobData = deserialized;
                    cache.put(uuid, playerJobData);
                } else {
                    plugin.getLogger().info("PDCStorage.load: no PDC data found for " + uuid + ", cache currently has " + cache.size() + " entries");
                    // If there's no PDC data but the cache already contains data (e.g. populated
                    // from leaderboard.yml at startup), preserve that cached data instead of
                    // overwriting it with an empty PlayerJobData. This prevents YAML-loaded
                    // levels from being lost when the player joins.
                    PlayerJobData existing = cache.get(uuid);
                    if (existing != null) {
                        playerJobData = existing;
                        // Diagnostic: log that we preserved YAML cache
                        StringBuilder jobLevels = new StringBuilder();
                        for (Map.Entry<String, PlayerJobData.JobStats> statsEntry : existing.getJobStats().entrySet()) {
                            jobLevels.append(statsEntry.getKey()).append("=").append(statsEntry.getValue().getLevel()).append(" ");
                        }
                        plugin.getLogger().info("PDCStorage.load: preserved YAML-populated cache for " + uuid + " with jobs: " + jobLevels.toString().trim());
                    } else {
                        playerJobData = new PlayerJobData(uuid);
                        cache.put(uuid, playerJobData);
                        plugin.getLogger().info("PDCStorage.load: created empty cache entry for " + uuid);
                    }
                }
                future.complete(playerJobData);
                plugin.getLogger().info("PDCStorage.load: completed async load for " + uuid);
            }, () -> {
                //fail
                plugin.getLogger().warning("PDCStorage.load: async task failed for " + uuid + "! storage: PDC");
                future.cancel(false);
            });
        } else {
            //todo implement offlineplayer data load
            //currently not needed
            plugin.getLogger().warning("PDCStorage.load: player " + uuid + " not found online");
        }

        return future;
    }

    @Override
    public boolean isCached(UUID uuid) {
        return cache.containsKey(uuid);
    }

    @Override
    public @Nullable PlayerJobData getCached(UUID uuid) {
        return cache.getOrDefault(uuid, null);
    }

    @Override
    public void set(UUID uuid, PlayerJobData playerJobData) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {

            return;
        }
        cache.put(uuid, playerJobData);
        set(player, playerJobData);
    }

    private void set(Player player, PlayerJobData playerJobData) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(TAG_JOBS_DATA, PersistentDataType.TAG_CONTAINER, serialize(pdc.getAdapterContext(), playerJobData));
    }

    @Override
    public void remove(UUID uuid) {
        removeFromCache(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.remove(TAG_JOBS_DATA);
    }

    @Override
    public void removeFromCache(UUID uuid) {
        cache.remove(uuid);
    }
    

}
