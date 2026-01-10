package me.usainsrht.ujobs.listeners.job_actions;

import me.usainsrht.ujobs.managers.JobManager;
import me.usainsrht.ujobs.models.BuiltInActions;
import me.usainsrht.ujobs.models.Job;
import me.usainsrht.ujobs.utils.JobExpUtils;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TravelListener implements Listener {

    JobManager jobManager;
    private final Map<UUID, Long> playerLastBlockPos = new HashMap<>(); // Track last block position as encoded long
    private final Map<UUID, Integer> playerCumulativeBlocks = new HashMap<>(); // Track cumulative blocks in current session
    private final Map<UUID, Map<String, Integer>> playerAwardedCounts = new HashMap<>(); // Track how many times thresholds were awarded per job
    private static final long UNSET = Long.MIN_VALUE; // Sentinel value for unset position

    public TravelListener(JobManager jobManager) {
        this.jobManager = jobManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player player = e.getPlayer();
        boolean shouldIgnore = jobManager.shouldIgnore(player);
        if (shouldIgnore) return;

        // Check if player is on land (not in water or lava) using block-under check
        boolean onLand = isOnLand(player);

        // If in water/lava, clear last position only; do NOT reset cumulative blocks.
        // If temporarily off-ground (jump/fall), do not count movement but preserve cumulative.
        UUID uuidTemp = player.getUniqueId();
        if (player.isInWater() || player.isInLava()) {
            playerLastBlockPos.remove(uuidTemp);
            return;
        }
        if (!onLand) return;

        // Get block coordinates (full blocks only)
        int toBlockX = e.getTo().getBlockX();
        int toBlockZ = e.getTo().getBlockZ();
        long currentPos = encodePos(toBlockX, toBlockZ);

        UUID uuid = player.getUniqueId();
        long lastPos = playerLastBlockPos.getOrDefault(uuid, UNSET);

        // Only count if player moved to a different block
        if (currentPos == lastPos) {
            return;
        }

        // Don't calculate distance on first position (UNSET)
        if (lastPos == UNSET) {
            playerLastBlockPos.put(uuid, currentPos);
            return;
        }

        playerLastBlockPos.put(uuid, currentPos);

        // Calculate blocks moved (Manhattan distance on x,z plane)
        int lastBlockX = decodeX(lastPos);
        int lastBlockZ = decodeZ(lastPos);
        int blocksMoved = Math.abs(toBlockX - lastBlockX) + Math.abs(toBlockZ - lastBlockZ);

        // movement detected; accumulate blocks (no debug logging)

        // Accumulate blocks for this session (preserve cumulative between off-land)
        int cumulativeBlocks = playerCumulativeBlocks.getOrDefault(uuid, 0) + blocksMoved;
        playerCumulativeBlocks.put(uuid, cumulativeBlocks);

        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Special.TRAVEL)) {
            for (Job job : jobManager.getJobsWithAction(BuiltInActions.Special.TRAVEL)) {
                // Determine best threshold configured for this cumulative amount
                int threshold = job.getBestThresholdForAmount(BuiltInActions.Special.TRAVEL, cumulativeBlocks);
                if (threshold <= 0) continue;

                Job.ActionReward reward = job.getActionReward(BuiltInActions.Special.TRAVEL, String.valueOf(threshold));
                if (reward == null) continue;

                // Track how many times this player has already been awarded for this job
                Map<String, Integer> awarded = playerAwardedCounts.computeIfAbsent(uuid, k -> new HashMap<>());
                int already = awarded.getOrDefault(job.getId(), 0);
                int timesShouldHave = cumulativeBlocks / threshold;
                int delta = timesShouldHave - already;
                if (delta > 0) {
                    JobExpUtils.processJobExp(player, job, reward, delta);
                    awarded.put(job.getId(), timesShouldHave);
                }
            }
        } else {
            jobManager.getPlugin().getLogger().warning("TravelListener: TRAVEL action NOT found in action map. Available actions: " + jobManager.getActionJobMap().keySet());
        }
    }

    /**
     * Encode x, z coordinates into a single long for quick comparison.
     */
    private long encodePos(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private int decodeX(long pos) {
        return (int) (pos >> 32);
    }

    private int decodeZ(long pos) {
        return (int) pos;
    }

    /**
     * Check if player is standing on solid land (not in water, lava, or air).
     */
    private boolean isOnLand(Player player) {
        if (player.isInWater() || player.isInLava()) {
            return false;
        }
        // Use block-under check because isOnGround() can be unreliable over stairs/half slabs
        try {
            Block below = player.getLocation().getBlock().getRelative(0, -1, 0);
            Material t = below.getType();
            if (t.isAir()) return false;
            String name = t.name().toUpperCase();
            if (name.contains("WATER") || name.contains("LAVA")) return false;
            return true;
        } catch (Exception ex) {
            return player.isOnGround();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        // Clean up position map when player leaves
        UUID uuid = e.getPlayer().getUniqueId();
        playerLastBlockPos.remove(uuid);
        playerCumulativeBlocks.remove(uuid);
    }
}
