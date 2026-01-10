package me.usainsrht.ujobs.managers;

import lombok.Getter;
import me.usainsrht.ujobs.UJobsPlugin;
import me.usainsrht.ujobs.models.Job;
import me.usainsrht.ujobs.models.PlayerJobData;
import me.usainsrht.ujobs.models.PlayerLeaderboardData;
import me.usainsrht.ujobs.storage.PDCStorage;
import me.usainsrht.ujobs.utils.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;
import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
public class LeaderboardManager {

    UJobsPlugin plugin;
    Map<UUID, PlayerLeaderboardData> leaderboardPlayerCache;
    Map<Job, UUID[]> leaderboardJobCache;

    public LeaderboardManager(UJobsPlugin plugin) {
        this.plugin = plugin;
        this.leaderboardPlayerCache = new HashMap<>();
        this.leaderboardJobCache = new HashMap<>();
    }

    /**
     * Resolve a player's display name using (in order): cached displayName, Bukkit OfflinePlayer, server usercache.json,
     * then fall back to a short uuid string.
     */
    public String resolvePlayerName(UUID uuid) {
        try {
            PlayerLeaderboardData cached = leaderboardPlayerCache.get(uuid);
            if (cached != null && cached.displayName != null && !cached.displayName.isEmpty()) return cached.displayName;

            OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
            if (off != null) {
                String n = off.getName();
                if (n != null && !n.isEmpty()) return n;
            }

            // Try server usercache.json (server root = world container)
            File usercache = new File(plugin.getServer().getWorldContainer(), "usercache.json");
            if (usercache.exists()) {
                String raw = Files.readString(usercache.toPath());
                Pattern p1 = Pattern.compile("\\\"uuid\\\"\\s*:\\s*\\\"" + uuid.toString() + "\\\"[^}]*\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.DOTALL);
                Matcher m1 = p1.matcher(raw);
                if (m1.find()) return m1.group(1);
                Pattern p2 = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[^}]*\\\"uuid\\\"\\s*:\\s*\\\"" + uuid.toString() + "\\\"", Pattern.DOTALL);
                Matcher m2 = p2.matcher(raw);
                if (m2.find()) return m2.group(1);
            }
        } catch (Exception ignored) {}
        return uuid.toString().substring(0, 8);
    }

    public void load(ConfigurationSection yml) {
        for (Job job : plugin.getJobManager().getJobs().values()) {
            leaderboardJobCache.put(job, new UUID[100]);
        }

        // Check if database exists (i.e., /jobs migrate has been run)
        boolean databaseExists = false;
        if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.SqliteStorage sqlite) {
            java.io.File dbFile = new java.io.File(plugin.getDataFolder(), "ujobs.db");
            databaseExists = dbFile.exists();
        }

        // If database exists, ignore YAML and PDC entirely - database is the exclusive source of truth
        if (databaseExists) {
            plugin.getLogger().info("LeaderboardManager: database exists, skipping YAML population (using database as source of truth)");
            // Populate leaderboards directly from the SQLite database so offline players are included
            try {
                if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.SqliteStorage sqlite) {
                    sqlite.ensureDatabase();
                    java.sql.Connection conn = sqlite.getDbConnection();
                    if (conn != null) {
                        int calculateTop = plugin.getConfig().getInt("leaderboard.calculate_top", 100);
                        for (Job job : plugin.getJobManager().getJobs().values()) {
                            UUID[] ordered = new UUID[Math.max(calculateTop, 100)];
                            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                                    "SELECT uuid, level, exp FROM player_jobs WHERE job_id = ? ORDER BY level DESC, exp DESC LIMIT ?")) {
                                ps.setString(1, job.getId());
                                ps.setInt(2, calculateTop);
                                try (java.sql.ResultSet rs = ps.executeQuery()) {
                                    int idx = 0;
                                    while (rs.next()) {
                                        String uuidStr = rs.getString("uuid");
                                        UUID uuid = UUID.fromString(uuidStr);
                                        int level = rs.getInt("level");
                                        // double exp = rs.getDouble("exp"); // not needed here
                                        ordered[idx] = uuid;
                                        PlayerLeaderboardData pld = leaderboardPlayerCache.computeIfAbsent(uuid, PlayerLeaderboardData::new);
                                        // set display name if available
                                        try { String name = Bukkit.getOfflinePlayer(uuid).getName(); if (name != null) pld.displayName = name; } catch (Exception ignored) {}
                                        pld.getLeaderboardStats().put(job, new PlayerLeaderboardData.LeaderboardStats(idx, level));
                                        idx++;
                                        if (idx >= ordered.length) break;
                                    }
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("LeaderboardManager: failed to read leaderboard from DB for job=" + job.getId() + ": " + e.getMessage());
                            }
                            leaderboardJobCache.put(job, ordered);
                        }
                    }
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("LeaderboardManager: error populating leaderboards from DB: " + ex.getMessage());
            }
            return;
        }

        // Before migration: load from leaderboard.yml
        ConfigurationSection leaderboardSection = yml.getConfigurationSection("leaderboard");
        if (leaderboardSection == null) return;
        leaderboardSection.getKeys(false).forEach(jobId -> {
            Job job = plugin.getJobManager().getJobs().get(jobId);
            if (job == null) return;

            ConfigurationSection jobSection = yml.getConfigurationSection("leaderboard." + jobId);
            if (jobSection == null) return;

            jobSection.getKeys(false).forEach(uuidString -> {
                UUID uuid = UUID.fromString(uuidString);
                int position = jobSection.getInt(uuidString + ".position", -1);
                int level = jobSection.getInt(uuidString + ".level", -1);
                double exp = jobSection.getDouble(uuidString + ".exp", 0.0);

                if (position < 0 || level < 0) return;

                PlayerLeaderboardData playerData = leaderboardPlayerCache.computeIfAbsent(uuid, PlayerLeaderboardData::new);
                // populate display name if available
                String name = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
                if (name != null) playerData.displayName = name;
                playerData.getLeaderboardStats().put(job, new PlayerLeaderboardData.LeaderboardStats(position, level));

                leaderboardJobCache.get(job)[position] = uuid;

                // Also populate PlayerJobData cache with XP
                PlayerJobData pjd = plugin.getStorage() instanceof me.usainsrht.ujobs.storage.SqliteStorage sqlite ? sqlite.getCache().get(uuid)
                    : plugin.getStorage() instanceof me.usainsrht.ujobs.storage.PDCStorage pdc ? pdc.getCache().get(uuid)
                    : null;
                if (pjd == null) {
                    pjd = new PlayerJobData(uuid);
                    if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.SqliteStorage sqlite) {
                        sqlite.getCache().put(uuid, pjd);
                    } else if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.PDCStorage pdc) {
                        pdc.getCache().put(uuid, pjd);
                    }
                }
                pjd.setJobStats(job.getId(), new PlayerJobData.JobStats(level, exp, 0.0));
            });
        });

        // If storage cache is empty for some players but leaderboard.yml contains their levels,
        // populate storage cache so runtime actions see correct levels. This helps when
        // people copy leaderboard.yml into a test server without PDC data.
        for (Map.Entry<UUID, PlayerLeaderboardData> entry : leaderboardPlayerCache.entrySet()) {
            UUID playerUuid = entry.getKey();
                if (plugin.getStorage().getCached(playerUuid) != null) continue;

            PlayerJobData pjd = new PlayerJobData(playerUuid);
            // copy leaderboard levels into player job stats (exp/totalMoney set to 0)
            for (Map.Entry<Job, PlayerLeaderboardData.LeaderboardStats> statEntry : entry.getValue().getLeaderboardStats().entrySet()) {
                Job job = statEntry.getKey();
                int level = statEntry.getValue().getLevel();
                pjd.setJobStats(job.getId(), new PlayerJobData.JobStats(level, 0, 0.0));
            }

            // Diagnostic: log which jobs were copied for this player
            StringBuilder jobLevels = new StringBuilder();
            for (Map.Entry<String, PlayerJobData.JobStats> statsEntry : pjd.getJobStats().entrySet()) {
                jobLevels.append(statsEntry.getKey()).append("=").append(statsEntry.getValue().getLevel()).append(" ");
            }

            // Populate the storage cache so runtime processing sees levels loaded from leaderboard.yml.
            // Only PDCStorage needs explicit cache population (SqliteStorage will load from DB on player join).
            if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.PDCStorage pdc) {
                pdc.getCache().put(playerUuid, pjd);
                plugin.getLogger().info("LeaderboardManager: populated PDC cache for " + playerUuid + " from leaderboard.yml (jobs=" + pjd.getJobStats().keySet().size() + ") levels: " + jobLevels.toString().trim());
            } else if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.SqliteStorage sqlite) {
                // For SqliteStorage, just populate the in-memory cache.
                // Migration will write YAML levels to database on /jobs migrate command.
                sqlite.getCache().put(playerUuid, pjd);
            }
        }

        // Recompute ordered arrays from YAML-loaded stats to ensure positions reflect levels
        int calculateTop = plugin.getConfig().getInt("leaderboard.calculate_top", 100);
        for (Job job : plugin.getJobManager().getJobs().values()) {
            UUID[] ordered = new UUID[Math.max(calculateTop, 100)];
            // collect players who have stats for this job
            List<Map.Entry<UUID, PlayerLeaderboardData.LeaderboardStats>> entries = new ArrayList<>();
            for (Map.Entry<UUID, PlayerLeaderboardData> e : leaderboardPlayerCache.entrySet()) {
                PlayerLeaderboardData.LeaderboardStats stats = e.getValue().getLeaderboardStats().get(job);
                if (stats != null) entries.add(new AbstractMap.SimpleEntry<>(e.getKey(), stats));
            }
            // sort by level desc, then by stored position asc for stability
            entries.sort((a, b) -> {
                int levelCmp = Integer.compare(b.getValue().getLevel(), a.getValue().getLevel());
                if (levelCmp != 0) return levelCmp;
                return Integer.compare(a.getValue().getPosition(), b.getValue().getPosition());
            });

            int idx = 0;
            for (Map.Entry<UUID, PlayerLeaderboardData.LeaderboardStats> e : entries) {
                if (idx >= ordered.length) break;
                ordered[idx] = e.getKey();
                // update cached position to match sorted index
                leaderboardPlayerCache.computeIfAbsent(e.getKey(), PlayerLeaderboardData::new)
                        .getLeaderboardStats().put(job, new PlayerLeaderboardData.LeaderboardStats(idx, e.getValue().getLevel()));
                idx++;
            }

            leaderboardJobCache.put(job, ordered);
        }

        // Rebuild leaderboards from storage only if storage cache is already populated.
        // For SqliteStorage, migration happens at startup so cache will be populated.
        // For PDCStorage cache is populated asynchronously as players join, so avoid overwriting
        // YAML-loaded leaderboards with an empty in-memory cache during startup.
        if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.SqliteStorage ||
            (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.PDCStorage pdcStorage && !pdcStorage.getCache().isEmpty())) {
            for (Job job : plugin.getJobManager().getJobs().values()) {
                UUID[] rebuilt = createLeaderboard(job);
                if (rebuilt != null) leaderboardJobCache.put(job, rebuilt);
            }
        }
    }

    public void save() {
        YamlConfiguration leaderboardConfig = plugin.getConfigManager().getLeaderboardConfig();

        leaderboardConfig.set("leaderboard", null); // Clear existing leaderboard data

        for (Job job : plugin.getJobManager().getJobs().values()) {
            if (!leaderboardJobCache.containsKey(job) || leaderboardJobCache.get(job) == null || leaderboardJobCache.get(job)[0] == null) {
                leaderboardJobCache.put(job, createLeaderboard(job));
            }
        }

        leaderboardPlayerCache.forEach(((uuid, playerLeaderboardData) -> {
            playerLeaderboardData.getLeaderboardStats().forEach((job, stats) -> {
                String path = "leaderboard." + job.getId() + "." + uuid.toString();
                leaderboardConfig.set(path + ".position", stats.getPosition());
                leaderboardConfig.set(path + ".level", stats.getLevel());
                // If we have cached PlayerJobData (XP) for this player, persist it too
                try {
                    me.usainsrht.ujobs.models.PlayerJobData pjd = plugin.getStorage().getCached(uuid);
                    if (pjd != null && pjd.hasJobStats(job.getId())) {
                        double exp = pjd.getJobStats(job.getId()).getExp();
                        // Only persist exp if it's a positive value (prevents creating zero/empty exp entries at startup)
                        if (exp > 0.0) {
                            leaderboardConfig.set(path + ".exp", exp);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }));

        plugin.getConfigManager().saveLeaderboard();
    }

    /**
     * Save all cached player levels and XP to leaderboard.yml before migration.
     */
    public void saveExpToLeaderboardYml() {
        if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.SqliteStorage sqlite) {
            java.io.File dbFile = new java.io.File(plugin.getDataFolder(), "ujobs.db");
            if (dbFile.exists()) {
                // If database exists, do not save to YAML
                return;
            }
            java.io.File leaderboardFile = new java.io.File(plugin.getDataFolder(), "leaderboard.yml");
            org.bukkit.configuration.file.YamlConfiguration yml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(leaderboardFile);
            for (Map.Entry<UUID, me.usainsrht.ujobs.models.PlayerJobData> entry : sqlite.getCache().entrySet()) {
                UUID uuid = entry.getKey();
                PlayerJobData pjd = entry.getValue();
                for (Map.Entry<String, PlayerJobData.JobStats> jobEntry : pjd.getJobStats().entrySet()) {
                    String jobId = jobEntry.getKey();
                    PlayerJobData.JobStats stats = jobEntry.getValue();
                    String basePath = "leaderboard." + jobId + "." + uuid;
                    yml.set(basePath + ".level", stats.getLevel());
                    if (stats.getExp() > 0.0) {
                        yml.set(basePath + ".exp", stats.getExp());
                    }
                    PlayerLeaderboardData.LeaderboardStats lbStats = leaderboardPlayerCache.containsKey(uuid) && leaderboardPlayerCache.get(uuid).getLeaderboardStats().containsKey(plugin.getJobManager().getJobs().get(jobId))
                            ? leaderboardPlayerCache.get(uuid).getLeaderboardStats().get(plugin.getJobManager().getJobs().get(jobId))
                            : null;
                    if (lbStats != null) {
                        yml.set(basePath + ".position", lbStats.getPosition());
                    }
                }
            }
            try {
                yml.save(leaderboardFile);
                plugin.getLogger().info("LeaderboardManager: saved player levels and XP to leaderboard.yml");
            } catch (Exception e) {
                plugin.getLogger().severe("LeaderboardManager: failed to save leaderboard.yml: " + e.getMessage());
            }
        } else if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.PDCStorage pdc) {
            java.io.File leaderboardFile = new java.io.File(plugin.getDataFolder(), "leaderboard.yml");
            org.bukkit.configuration.file.YamlConfiguration yml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(leaderboardFile);
            for (Map.Entry<UUID, me.usainsrht.ujobs.models.PlayerJobData> entry : pdc.getCache().entrySet()) {
                UUID uuid = entry.getKey();
                PlayerJobData pjd = entry.getValue();
                for (Map.Entry<String, PlayerJobData.JobStats> jobEntry : pjd.getJobStats().entrySet()) {
                    String jobId = jobEntry.getKey();
                    PlayerJobData.JobStats stats = jobEntry.getValue();
                    String basePath = "leaderboard." + jobId + "." + uuid;
                    yml.set(basePath + ".level", stats.getLevel());
                    if (stats.getExp() > 0.0) {
                        yml.set(basePath + ".exp", stats.getExp());
                    }
                    PlayerLeaderboardData.LeaderboardStats lbStats = leaderboardPlayerCache.containsKey(uuid) && leaderboardPlayerCache.get(uuid).getLeaderboardStats().containsKey(plugin.getJobManager().getJobs().get(jobId))
                            ? leaderboardPlayerCache.get(uuid).getLeaderboardStats().get(plugin.getJobManager().getJobs().get(jobId))
                            : null;
                    if (lbStats != null) {
                        yml.set(basePath + ".position", lbStats.getPosition());
                    }
                }
            }
            try {
                yml.save(leaderboardFile);
                plugin.getLogger().info("LeaderboardManager: saved player levels and XP to leaderboard.yml");
            } catch (Exception e) {
                plugin.getLogger().severe("LeaderboardManager: failed to save leaderboard.yml: " + e.getMessage());
            }
        }
    }

    /**
     * Persist levels and XP for a single player to leaderboard.yml. This will write the
     * `.exp` field even when it is zero — used when a player joins so their entry is created.
     */
    public void saveExpForPlayer(UUID uuid) {
        org.bukkit.configuration.file.YamlConfiguration yml = plugin.getConfigManager().getLeaderboardConfig();

        me.usainsrht.ujobs.models.PlayerJobData pjd = plugin.getStorage().getCached(uuid);
        if (pjd == null) return;

        for (Map.Entry<String, PlayerJobData.JobStats> jobEntry : pjd.getJobStats().entrySet()) {
            String jobId = jobEntry.getKey();
            PlayerJobData.JobStats stats = jobEntry.getValue();
            String basePath = "leaderboard." + jobId + "." + uuid;
            yml.set(basePath + ".level", stats.getLevel());
            // Force-writing exp even if zero for this player
            yml.set(basePath + ".exp", stats.getExp());
            PlayerLeaderboardData.LeaderboardStats lbStats = leaderboardPlayerCache.containsKey(uuid) && leaderboardPlayerCache.get(uuid).getLeaderboardStats().containsKey(plugin.getJobManager().getJobs().get(jobId))
                    ? leaderboardPlayerCache.get(uuid).getLeaderboardStats().get(plugin.getJobManager().getJobs().get(jobId))
                    : null;
            if (lbStats != null) {
                yml.set(basePath + ".position", lbStats.getPosition());
            }
            // Ensure leaderboardPlayerCache contains this player's leaderboard stats so GUI can show names/levels
            me.usainsrht.ujobs.models.Job job = plugin.getJobManager().getJobs().get(jobId);
            if (job != null) {
                me.usainsrht.ujobs.models.PlayerLeaderboardData pld = leaderboardPlayerCache.computeIfAbsent(uuid, me.usainsrht.ujobs.models.PlayerLeaderboardData::new);
                int pos = -1;
                me.usainsrht.ujobs.models.PlayerLeaderboardData.LeaderboardStats existing = pld.getLeaderboardStats().get(job);
                if (existing != null) pos = existing.getPosition();
                pld.getLeaderboardStats().put(job, new me.usainsrht.ujobs.models.PlayerLeaderboardData.LeaderboardStats(pos, stats.getLevel()));

                // Rebuild leaderboard array for this job so GUI shows up-to-date entries
                UUID[] rebuilt = createLeaderboardFromCache(job);
                if (rebuilt != null) leaderboardJobCache.put(job, rebuilt);
                plugin.getLogger().info("LeaderboardManager: saveExpForPlayer updated job=" + job.getId() + " for uuid=" + uuid + " level=" + stats.getLevel());
            }
        }

        plugin.getLogger().info("LeaderboardManager: updated leaderboard.yml config for " + uuid + " (call saveLeaderboard() to write file)");
    }

    public UUID[] createLeaderboard(Job job) {
        // Support PDCStorage (live players) and SqliteStorage (pre-migration cache or DB)
        if (plugin.getStorage() instanceof PDCStorage pdcStorage) {
            UUID[] leaderboard = new UUID[plugin.getConfig().getInt("leaderboard.calculate_top", 100)];
            leaderboardJobCache.put(job, leaderboard);

            for (PlayerJobData playerJobData : pdcStorage.getCache().values()) {
                int level = playerJobData.getJobStats(job.getId()).getLevel();
                if (level < 0) continue;
                // Find the correct index to insert based on level (descending)
                int insertIndex = 0;
                while (insertIndex < leaderboard.length && leaderboard[insertIndex] != null) {
                    UUID existingUuid = leaderboard[insertIndex];
                    int existingLevel = -1;
                    if (existingUuid != null) {
                        PlayerJobData existingPlayerJobData = pdcStorage.getCache().get(existingUuid);
                        if (existingPlayerJobData == null) break;
                        existingLevel = existingPlayerJobData.getJobStats(job.getId()).getLevel();
                    }
                    if (level > existingLevel) break;
                    insertIndex++;
                }
                if (insertIndex < leaderboard.length) {
                    // Shift lower entries down
                    System.arraycopy(leaderboard, insertIndex, leaderboard, insertIndex + 1, leaderboard.length - insertIndex - 1);
                    leaderboard[insertIndex] = playerJobData.getUuid();
                    leaderboardPlayerCache.computeIfAbsent(playerJobData.getUuid(), PlayerLeaderboardData::new)
                            .getLeaderboardStats().put(job, new PlayerLeaderboardData.LeaderboardStats(insertIndex, level));
                }
            }

            // Ensure leaderboard cache positions/levels are consistent for all entries
            for (int i = 0; i < leaderboard.length; i++) {
                UUID entry = leaderboard[i];
                if (entry == null) continue;
                PlayerJobData pjd = pdcStorage.getCache().get(entry);
                int lvl = -1;
                if (pjd != null) lvl = pjd.getJobStats(job.getId()).getLevel();
                // create or update the cached stats for this job
                leaderboardPlayerCache.computeIfAbsent(entry, PlayerLeaderboardData::new)
                        .getLeaderboardStats().put(job, new PlayerLeaderboardData.LeaderboardStats(i, lvl));
            }

            return leaderboard;
        } else if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.SqliteStorage sqlite) {
            // If a real DB exists and contains rows, use it as the authoritative source for leaderboards.
            try {
                if (sqlite.databaseHasRows()) {
                    try { sqlite.ensureDatabase(); } catch (Exception ignored) {}
                    java.sql.Connection conn = sqlite.getDbConnection();
                    if (conn != null) {
                        int calculateTop = plugin.getConfig().getInt("leaderboard.calculate_top", 100);
                        UUID[] ordered = new UUID[Math.max(calculateTop, 100)];
                        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                                "SELECT uuid, level, exp FROM player_jobs WHERE job_id = ? ORDER BY level DESC, exp DESC LIMIT ?")) {
                            ps.setString(1, job.getId());
                            ps.setInt(2, plugin.getConfig().getInt("leaderboard.calculate_top", 100));
                            try (java.sql.ResultSet rs = ps.executeQuery()) {
                                int idx = 0;
                                while (rs.next() && idx < ordered.length) {
                                    String uuidStr = rs.getString("uuid");
                                    UUID uuid = UUID.fromString(uuidStr);
                                    int level = rs.getInt("level");
                                    ordered[idx] = uuid;
                                    PlayerLeaderboardData pld = leaderboardPlayerCache.computeIfAbsent(uuid, PlayerLeaderboardData::new);
                                    try { String name = Bukkit.getOfflinePlayer(uuid).getName(); if (name != null) pld.displayName = name; } catch (Exception ignored) {}
                                    pld.getLeaderboardStats().put(job, new PlayerLeaderboardData.LeaderboardStats(idx, level));
                                    idx++;
                                }
                            }
                        }
                        leaderboardJobCache.put(job, ordered);
                        return ordered;
                    }
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("LeaderboardManager: error querying DB for leaderboard, falling back to cache: " + ex.getMessage());
            }

            // No authoritative DB: ensure any offline playerdata preloads are read and fall back to cache/YAML
            try { sqlite.preloadOfflinePdcToCache(); } catch (Exception ignored) {}

            UUID[] leaderboard = new UUID[plugin.getConfig().getInt("leaderboard.calculate_top", 100)];
            leaderboardJobCache.put(job, leaderboard);

            // Collect levels from sqlite cache
            java.util.List<Map.Entry<UUID,Integer>> entries = new java.util.ArrayList<>();
            for (me.usainsrht.ujobs.models.PlayerJobData pjd : sqlite.getCache().values()) {
                if (pjd == null) continue;
                if (!pjd.hasJobStats(job.getId())) continue;
                int lvl = pjd.getJobStats(job.getId()).getLevel();
                entries.add(new AbstractMap.SimpleEntry<>(pjd.getUuid(), lvl));
            }

            // Also include entries present in leaderboardPlayerCache (YAML) if not already present
            for (Map.Entry<UUID, PlayerLeaderboardData> e : leaderboardPlayerCache.entrySet()) {
                UUID uuid = e.getKey();
                PlayerLeaderboardData.LeaderboardStats stats = e.getValue().getLeaderboardStats().get(job);
                if (stats == null) continue;
                boolean found = false;
                for (Map.Entry<UUID,Integer> en : entries) if (en.getKey().equals(uuid)) { found = true; break; }
                if (!found) entries.add(new AbstractMap.SimpleEntry<>(uuid, stats.getLevel()));
            }

            // Sort entries by level desc
            entries.sort((a,b) -> Integer.compare(b.getValue(), a.getValue()));

            int idx = 0;
            for (Map.Entry<UUID,Integer> e : entries) {
                if (idx >= leaderboard.length) break;
                leaderboard[idx] = e.getKey();
                leaderboardPlayerCache.computeIfAbsent(e.getKey(), PlayerLeaderboardData::new)
                        .getLeaderboardStats().put(job, new PlayerLeaderboardData.LeaderboardStats(idx, e.getValue()));
                idx++;
            }

            return leaderboard;
        }
        return null;
    }

    /**
     * Rebuild leaderboard array for a job using the current `leaderboardPlayerCache` data.
     * This is used when leaderboards are driven from YAML/pre-migration cache.
     */
    public UUID[] createLeaderboardFromCache(Job job) {
        int calculateTop = plugin.getConfig().getInt("leaderboard.calculate_top", 100);
        UUID[] ordered = new UUID[Math.max(calculateTop, 100)];

        // collect players who have stats for this job
        List<Map.Entry<UUID, PlayerLeaderboardData.LeaderboardStats>> entries = new ArrayList<>();
        for (Map.Entry<UUID, PlayerLeaderboardData> e : leaderboardPlayerCache.entrySet()) {
            PlayerLeaderboardData.LeaderboardStats stats = e.getValue().getLeaderboardStats().get(job);
            if (stats != null) entries.add(new AbstractMap.SimpleEntry<>(e.getKey(), stats));
        }

        plugin.getLogger().info("LeaderboardManager: createLeaderboardFromCache for job=" + job.getId() + " found entries=" + entries.size());

        // sort by level desc, then by stored position asc for stability
        entries.sort((a, b) -> {
            int levelCmp = Integer.compare(b.getValue().getLevel(), a.getValue().getLevel());
            if (levelCmp != 0) return levelCmp;
            return Integer.compare(a.getValue().getPosition(), b.getValue().getPosition());
        });

        int idx = 0;
        for (Map.Entry<UUID, PlayerLeaderboardData.LeaderboardStats> e : entries) {
            if (idx >= ordered.length) break;
            ordered[idx] = e.getKey();
            // update cached position to match sorted index
            leaderboardPlayerCache.computeIfAbsent(e.getKey(), PlayerLeaderboardData::new)
                    .getLeaderboardStats().put(job, new PlayerLeaderboardData.LeaderboardStats(idx, e.getValue().getLevel()));
            idx++;
        }

        plugin.getLogger().info("LeaderboardManager: createLeaderboardFromCache for job=" + job.getId() + " built top=" + Math.min(idx, ordered.length));

        return ordered;
    }

    public int getPosition(UUID uuid, Job job) {
        PlayerLeaderboardData data = leaderboardPlayerCache.get(uuid);
        if (data == null) return -1;
        PlayerLeaderboardData.LeaderboardStats stats = data.getLeaderboardStats().get(job);
        if (stats == null) return -1;
        return stats.getPosition();
    }

    public int getLevel(UUID uuid, Job job) {
        PlayerLeaderboardData data = leaderboardPlayerCache.get(uuid);
        if (data == null) return -1;
        PlayerLeaderboardData.LeaderboardStats stats = data.getLeaderboardStats().get(job);
        if (stats == null) return -1;
        return stats.getLevel();
    }

    public PlayerLeaderboardData.LeaderboardStats getStats(UUID uuid, Job job) {
        PlayerLeaderboardData data = leaderboardPlayerCache.get(uuid);
        if (data == null) return null;
        return data.getLeaderboardStats().get(job);
    }

    public UUID getPlayerByPosition(int position, Job job) {
        if (position < 0) return null;
        UUID[] leaderboard = leaderboardJobCache.get(job);
        if (leaderboard == null || position >= leaderboard.length) return null;
        return leaderboard[position];
    }

    public void checkLeaderboardChange(UUID uuid, Job job, int level) {
        // Ensure leaderboardPlayerCache contains this player's up-to-date level so rebuilds include them
        leaderboardPlayerCache.computeIfAbsent(uuid, PlayerLeaderboardData::new)
                .getLeaderboardStats().computeIfAbsent(job, k -> new PlayerLeaderboardData.LeaderboardStats(-1, level))
                .setLevel(level);

        int position = getPosition(uuid, job);
        int oneHigher;
        UUID opponent;
        int calculateTop = plugin.getConfig().getInt("leaderboard.calculate_top", 100);
        if (position == -1) {
            // if a player is not in the leaderboard, get the last player in the leaderboard as an opponent
            oneHigher = calculateTop - 1;
            opponent = null;
            while (oneHigher >= 0) {
                opponent = getPlayerByPosition(oneHigher, job);
                if (opponent != null && !opponent.equals(uuid)) break;
                oneHigher--;
            }
        } else {

            leaderboardPlayerCache.get(uuid).getLeaderboardStats().get(job).setLevel(level);

            oneHigher = position - 1;
            if (oneHigher < 0) return;
            opponent = getPlayerByPosition(oneHigher, job);
        }

        if (opponent == null) {
            // No opponent found (empty leaderboard). Rebuild from cache so this player's new level is considered immediately.
            UUID[] rebuilt = createLeaderboardFromCache(job);
            if (rebuilt != null) leaderboardJobCache.put(job, rebuilt);

            // If rebuilding placed the player at first, announce the takeover now.
            int newPos = getPosition(uuid, job);
            if (newPos == 0) {
                UUID prevOpponent = null;
                if (newPos - 1 >= 0) prevOpponent = getPlayerByPosition(newPos - 1, job);
                OfflinePlayer opponentPlayer = prevOpponent != null ? Bukkit.getOfflinePlayer(prevOpponent) : null;
                OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);

                Set<TagResolver> placeholderSet = new HashSet<>();
                if (opponentPlayer != null) {
                    placeholderSet.add(Placeholder.component("opponent_displayname", opponentPlayer.isOnline() ? opponentPlayer.getPlayer().displayName() : Component.text(opponentPlayer.getName())));
                    placeholderSet.add(Placeholder.unparsed("opponent_name", opponentPlayer.getName()));
                } else {
                    placeholderSet.add(Placeholder.component("opponent_displayname", Component.text("")));
                    placeholderSet.add(Placeholder.unparsed("opponent_name", ""));
                }
                placeholderSet.add(Placeholder.component("displayname", player.isOnline() ? player.getPlayer().displayName() : Component.text(player.getName())));
                placeholderSet.add(Placeholder.unparsed("name", player.getName()));
                placeholderSet.add(Formatter.number("level", level));
                placeholderSet.add(Formatter.number("position", newPos + 1));
                placeholderSet.add(Placeholder.component("job", job.getName()));
                placeholderSet.add(Placeholder.styling("primary", job.getName().children().getFirst().color()));
                placeholderSet.add(Placeholder.styling("secondary", job.getName().children().getLast().color()));

                TagResolver[] placeholders = placeholderSet.toArray(new TagResolver[]{});

                if (player.isOnline()) MessageUtil.send(player.getPlayer(), plugin.getConfigManager().getMessage("leaderboard_take_someones_position"), placeholders);
                if (opponentPlayer != null && opponentPlayer.isOnline()) MessageUtil.send(opponentPlayer.getPlayer(), plugin.getConfigManager().getMessage("leaderboard_your_position_taken"), placeholders);
                MessageUtil.send(plugin.getServer(), plugin.getConfigManager().getMessage("leaderboard_take_lead"), placeholders);
                save();
            }
            return;
        }

        if (opponent.equals(uuid)) return;

        int opponentLevel = leaderboardPlayerCache.get(opponent).getLeaderboardStats().get(job).getLevel();
        if (level > opponentLevel) {

            leaderboardPlayerCache.computeIfAbsent(uuid, PlayerLeaderboardData::new)
                    .getLeaderboardStats().computeIfAbsent(job, k -> new PlayerLeaderboardData.LeaderboardStats(-1, 0))
                    .setPosition(oneHigher);
            leaderboardPlayerCache.get(uuid).getLeaderboardStats().get(job).setLevel(level);

            leaderboardPlayerCache.get(opponent).getLeaderboardStats().get(job).setPosition(position);

            leaderboardJobCache.get(job)[oneHigher] = uuid;
            if (position != -1) leaderboardJobCache.get(job)[position] = opponent;

            // Prepare placeholders and messages
            OfflinePlayer opponentPlayer = Bukkit.getOfflinePlayer(opponent);
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);

            Set<TagResolver> placeholderSet = new HashSet<>();
            placeholderSet.add(Placeholder.component("opponent_displayname", opponentPlayer.isOnline() ? opponentPlayer.getPlayer().displayName() : Component.text(opponentPlayer.getName())));
            placeholderSet.add(Placeholder.unparsed("opponent_name", opponentPlayer.getName()));
            placeholderSet.add(Placeholder.component("displayname", player.isOnline() ? player.getPlayer().displayName() : Component.text(player.getName())));
            placeholderSet.add(Placeholder.unparsed("name", player.getName()));
            placeholderSet.add(Formatter.number("level", level));
            placeholderSet.add(Formatter.number("position", oneHigher+1));
            placeholderSet.add(Placeholder.component("job", job.getName()));
            placeholderSet.add(Placeholder.styling("primary", job.getName().children().getFirst().color()));
            placeholderSet.add(Placeholder.styling("secondary", job.getName().children().getLast().color()));

            TagResolver[] placeholders = placeholderSet.toArray(new TagResolver[]{});

            if (player.isOnline()) MessageUtil.send(player.getPlayer(), plugin.getConfigManager().getMessage("leaderboard_take_someones_position"), placeholders);
            if (opponentPlayer.isOnline()) MessageUtil.send(opponentPlayer.getPlayer(), plugin.getConfigManager().getMessage("leaderboard_your_position_taken"), placeholders);

            if (oneHigher == 0) {
                MessageUtil.send(plugin.getServer(), plugin.getConfigManager().getMessage("leaderboard_take_lead"), placeholders);
            } else if (oneHigher == 9) {
                MessageUtil.send(plugin.getServer(), plugin.getConfigManager().getMessage("leaderboard_get_in_top_10"), placeholders);
            }

            save();
        } else {
            //check if one position below opponent is in calculate list and is empty
            int belowOpponent = oneHigher + 1;
            if (belowOpponent < calculateTop && belowOpponent > 0) {
                UUID[] leaderboard = leaderboardJobCache.get(job);
                if (leaderboard[belowOpponent] == null) {
                    leaderboard[belowOpponent] = uuid;

                    leaderboardPlayerCache.computeIfAbsent(uuid, PlayerLeaderboardData::new)
                            .getLeaderboardStats().computeIfAbsent(job, k -> new PlayerLeaderboardData.LeaderboardStats(-1, 0))
                            .setPosition(belowOpponent);
                    leaderboardPlayerCache.get(uuid).getLeaderboardStats().get(job).setLevel(level);

                    save();
                }
            }
        }
    }
}
