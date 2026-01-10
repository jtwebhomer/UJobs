package me.usainsrht.ujobs.models;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Getter
public class PlayerJobData {
    private final UUID uuid;
    private final Map<String, JobStats> jobStats;
    private boolean dirty = false;

    public PlayerJobData(UUID playerId) {
        this.uuid = playerId;
        this.jobStats = new HashMap<>();
    }

    public JobStats getJobStats(String jobId) {
        return jobStats.computeIfAbsent(jobId, k -> {
            JobStats s = new JobStats();
            s.setOwner(this);
            return s;
        });
    }

    public void setJobStats(String jobId, JobStats stats) {
        // Ensure the JobStats knows its owning PlayerJobData so setters can mark dirty
        stats.setOwner(this);
        jobStats.put(jobId, stats);
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    public boolean hasJobStats(String jobId) {
        return jobStats.containsKey(jobId);
    }

    public void addExp(String jobId, double exp) {
        JobStats stats = getJobStats(jobId);
        stats.setExp(stats.getExp() + exp);
        markDirty();
    }

    public void addMoney(String jobId, double money) {
        JobStats stats = getJobStats(jobId);
        stats.setTotalMoney(stats.getTotalMoney() + money);
        markDirty();
    }

    public void levelUp(String jobId) {
        JobStats stats = getJobStats(jobId);
        stats.setLevel(stats.getLevel() + 1);
        markDirty();
    }

    @Getter
    public static class JobStats {
        private int level;
        private double exp;
        private double totalMoney;

        // transient owner reference; not part of serialization
        private transient PlayerJobData owner;

        public JobStats() {
            this(0, 0, 0.0);
        }

        public JobStats(int level, double exp, double totalMoney) {
            this.level = level;
            this.exp = exp;
            this.totalMoney = totalMoney;
        }

        void setOwner(PlayerJobData owner) {
            this.owner = owner;
        }

        public void setLevel(int level) {
            this.level = level;
            if (owner != null) owner.markDirty();
        }

        public void setExp(double exp) {
            this.exp = exp;
            if (owner != null) owner.markDirty();
        }

        public void setTotalMoney(double totalMoney) {
            this.totalMoney = totalMoney;
            if (owner != null) owner.markDirty();
        }
    }
}