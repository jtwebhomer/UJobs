package me.usainsrht.ujobs.listeners;

import me.usainsrht.ujobs.UJobsPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

public class JoinListener implements Listener {

    UJobsPlugin plugin;

    public JoinListener(UJobsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        plugin.getLogger().info("JoinListener: " + player.getName() + " joined with UUID: " + uuid);
        // Ensure cache is synchronously populated (leaderboard / preload / live PDC) before async loads
        try {
            if (plugin.getStorage() instanceof me.usainsrht.ujobs.storage.SqliteStorage sqlite) {
                sqlite.ensureCachePopulatedOnJoin(uuid, player);
                // If we transferred/merged preloaded data, persist just this player's exp (including zero)
                if (!plugin.getLeaderboardManager().getLeaderboardPlayerCache().containsKey(uuid) && sqlite.hasPreloaded(uuid)) {
                    plugin.getLeaderboardManager().saveExpForPlayer(uuid);
                    plugin.getConfigManager().saveLeaderboard();
                    plugin.getLogger().info("JoinListener: applied preloaded PDC for " + uuid + " and saved to leaderboard.yml (sync)");
                }
            }
        } catch (Exception ignored) {}

        // Log digger XP specifically after ensuring cache is populated so it reflects current state immediately
        try {
            me.usainsrht.ujobs.models.PlayerJobData pjd = plugin.getStorage().getCached(uuid);
            double diggerExp = 0.0;
            int diggerLevel = -1;
            if (pjd != null && pjd.hasJobStats("digger")) {
                me.usainsrht.ujobs.models.PlayerJobData.JobStats ds = pjd.getJobStats("digger");
                diggerExp = ds.getExp();
                diggerLevel = ds.getLevel();
            }
            plugin.getLogger().info("UJobs: join digger XP for " + uuid + " level=" + diggerLevel + " exp=" + diggerExp);
        } catch (Exception ignored) {}

        // Continue with async load for DB-backed storages
        plugin.getStorage().load(uuid);
    }

}
