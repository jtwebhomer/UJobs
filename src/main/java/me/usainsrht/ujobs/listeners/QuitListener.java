package me.usainsrht.ujobs.listeners;

import me.usainsrht.ujobs.UJobsPlugin;
import me.usainsrht.ujobs.models.PlayerJobData;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.Map;

public class QuitListener implements Listener {

    UJobsPlugin plugin;

    public QuitListener(UJobsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        // Merge player's PDC into storage cache so leaderboard.yml will include their latest XP
        try {
            // Use the same NamespacedKey constants as PDCStorage so keys match exactly
            org.bukkit.NamespacedKey tagJobs = me.usainsrht.ujobs.storage.PDCStorage.TAG_JOBS_DATA;
            org.bukkit.NamespacedKey tagLevel = me.usainsrht.ujobs.storage.PDCStorage.TAG_LEVEL;
            org.bukkit.NamespacedKey tagExp = me.usainsrht.ujobs.storage.PDCStorage.TAG_EXP;
            org.bukkit.NamespacedKey tagXp = new org.bukkit.NamespacedKey("ujobs", "xp");
            org.bukkit.NamespacedKey tagTotalMoney = me.usainsrht.ujobs.storage.PDCStorage.TAG_TOTAL_MONEY;

            if (player.getPersistentDataContainer().has(tagJobs, PersistentDataType.TAG_CONTAINER)) {
                PlayerJobData pjd = plugin.getStorage().getCached(uuid);
                if (pjd == null) pjd = new PlayerJobData(uuid);

                PersistentDataType<org.bukkit.persistence.PersistentDataContainer, org.bukkit.persistence.PersistentDataContainer> pdt = PersistentDataType.TAG_CONTAINER;
                org.bukkit.persistence.PersistentDataContainer jobsContainer = player.getPersistentDataContainer().get(tagJobs, pdt);
                if (jobsContainer != null) {
                    for (org.bukkit.NamespacedKey jobKey : jobsContainer.getKeys()) {
                        if (!jobsContainer.has(jobKey, pdt)) continue;
                        org.bukkit.persistence.PersistentDataContainer jobContainer = jobsContainer.get(jobKey, pdt);
                        int level = jobContainer.getOrDefault(tagLevel, PersistentDataType.INTEGER, 0);
                        double exp = 0.0;
                        if (jobContainer.has(tagExp, PersistentDataType.DOUBLE)) {
                            exp = jobContainer.get(tagExp, PersistentDataType.DOUBLE);
                        } else if (jobContainer.has(tagXp, PersistentDataType.DOUBLE)) {
                            exp = jobContainer.get(tagXp, PersistentDataType.DOUBLE);
                        }
                        double totalMoney = jobContainer.getOrDefault(tagTotalMoney, PersistentDataType.DOUBLE, 0.0);

                        // Merge PDC values into pjd but prefer the in-memory cache if it has newer/higher values.
                        if (pjd.hasJobStats(jobKey.getKey())) {
                            PlayerJobData.JobStats existing = pjd.getJobStats(jobKey.getKey());
                            boolean replace = false;
                            if (level > existing.getLevel()) replace = true;
                            else if (level == existing.getLevel() && exp > existing.getExp()) replace = true;
                            if (replace) {
                                pjd.setJobStats(jobKey.getKey(), new PlayerJobData.JobStats(level, exp, totalMoney));
                            }
                        } else {
                            pjd.setJobStats(jobKey.getKey(), new PlayerJobData.JobStats(level, exp, totalMoney));
                        }
                    }
                }
                // For PDCStorage, avoid writing back to the player's PDC on quit; instead
                // update the in-memory cache so leaderboard.yml can be written.
                if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.PDCStorage pdc) {
                    pdc.getCache().put(uuid, pjd);
                } else {
                    plugin.getStorage().set(uuid, pjd);
                }
            }
        } catch (Exception ignored) {}

        PlayerJobData playerJobData = plugin.getStorage().getCached(uuid);
        if (playerJobData == null) return;

        // Update leaderboard in-memory cache to reflect this player's latest levels
        try {
            for (Map.Entry<String, PlayerJobData.JobStats> entry : playerJobData.getJobStats().entrySet()) {
                String jobId = entry.getKey();
                me.usainsrht.ujobs.models.Job job = plugin.getJobManager().getJobs().get(jobId);
                if (job == null) continue;
                int level = entry.getValue().getLevel();
                me.usainsrht.ujobs.models.PlayerLeaderboardData pld = plugin.getLeaderboardManager().getLeaderboardPlayerCache().computeIfAbsent(uuid, me.usainsrht.ujobs.models.PlayerLeaderboardData::new);
                me.usainsrht.ujobs.models.PlayerLeaderboardData.LeaderboardStats existing = pld.getLeaderboardStats().get(job);
                int pos = existing != null ? existing.getPosition() : -1;
                pld.getLeaderboardStats().put(job, new me.usainsrht.ujobs.models.PlayerLeaderboardData.LeaderboardStats(pos, level));
            }
        } catch (Exception ignored) {}

        // Log digger XP specifically on quit
        try {
            double diggerExp = 0.0;
            int diggerLevel = -1;
            if (playerJobData.hasJobStats("digger")) {
                PlayerJobData.JobStats ds = playerJobData.getJobStats("digger");
                diggerExp = ds.getExp();
                diggerLevel = ds.getLevel();
            }
            plugin.getLogger().info("UJobs: quit digger XP for " + uuid + " level=" + diggerLevel + " exp=" + diggerExp);
        } catch (Exception ignored) {}

        if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.PDCStorage) {
            // Don't persist to PDC on quit; write this player's exp to leaderboard.yml instead.
            plugin.getLeaderboardManager().saveExpForPlayer(uuid);
            plugin.getConfigManager().saveLeaderboard();
        } else if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.SqliteStorage) {
            // For sqlite storage, save the player's cache (will write to DB if DB exists).
            plugin.getStorage().save(uuid);
            java.io.File dbFile = new java.io.File(plugin.getDataFolder(), "ujobs.db");
            if (!dbFile.exists()) {
                // pre-migration: persist only this player's exp to leaderboard.yml
                plugin.getLeaderboardManager().saveExpForPlayer(uuid);
                plugin.getConfigManager().saveLeaderboard();
            }
        } else {
            // Default: save via storage and persist this player's exp
            plugin.getStorage().save(uuid);
            plugin.getLeaderboardManager().saveExpForPlayer(uuid);
            plugin.getConfigManager().saveLeaderboard();
        }

        plugin.getStorage().removeFromCache(uuid);
    }

}
