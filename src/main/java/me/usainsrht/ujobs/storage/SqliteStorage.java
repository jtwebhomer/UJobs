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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Getter
public class SqliteStorage implements Storage {

    UJobsPlugin plugin;
    HashMap<UUID, PlayerJobData> cache;
    // Preloaded offline PDC entries (do not write these to leaderboard.yml until player logs in)
    private final java.util.concurrent.ConcurrentHashMap<UUID, PlayerJobData> preloadCache = new java.util.concurrent.ConcurrentHashMap<>();
    Connection dbConnection;
    private static final String DB_FILENAME = "ujobs.db";
    private static final String TEMP_DB_FILENAME = "ujobs.db.tmp";
    private static final String MIGRATION_MARKER = "ujobs.db.migrating";
    private static final String BACKUP_1 = "ujobs.db.bak1";
    private static final String BACKUP_2 = "ujobs.db.bak2";
    // Prevent repeated NBT-reader warnings
    private boolean nbtReaderWarned = false;
    private static final NamespacedKey TAG_JOBS_DATA = new NamespacedKey("ujobs", "jobs_data");
    private static final NamespacedKey TAG_LEVEL = new NamespacedKey("ujobs", "level");
    private static final NamespacedKey TAG_EXP = new NamespacedKey("ujobs", "exp");
    private static final NamespacedKey TAG_XP = new NamespacedKey("ujobs", "xp");
    private static final NamespacedKey TAG_TOTAL_MONEY = new NamespacedKey("ujobs", "total_money");

    public SqliteStorage(UJobsPlugin plugin) {
        this.plugin = plugin;
        this.cache = new HashMap<>();
        // Database initialization is now lazy - happens only on /jobs migrate
    }

    /**
     * Initialize database connection. Called only when needed (e.g., during /jobs migrate).
     */
    public void ensureDatabase() {
        if (dbConnection != null) return; // Already initialized

        try {
            File dbFile = new File(plugin.getDataFolder(), DB_FILENAME);
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            dbConnection = DriverManager.getConnection(url);
            plugin.getLogger().info("SqliteStorage: connected to database at " + dbFile.getAbsolutePath());

            // Create tables if they don't exist
            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS player_jobs (" +
                        "uuid TEXT NOT NULL," +
                        "job_id TEXT NOT NULL," +
                        "level INTEGER DEFAULT 0," +
                        "exp REAL DEFAULT 0," +
                        "PRIMARY KEY (uuid, job_id))");
                plugin.getLogger().info("SqliteStorage: database tables created/verified");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("SqliteStorage: failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Check if the database file exists (i.e., migration has been run).
     */
    private boolean databaseExists() {
        File dbFile = new File(plugin.getDataFolder(), DB_FILENAME);
        // If a migration marker exists, treat the DB as non-authoritative (incomplete migration)
        File marker = new File(plugin.getDataFolder(), MIGRATION_MARKER);
        if (marker.exists()) {
            plugin.getLogger().warning("SqliteStorage: migration marker present; treating database as not ready");
            return false;
        }
        return dbFile.exists();
    }

    /**
     * Returns true if the database exists and contains at least one player_jobs row.
     * This is used to determine whether the DB is truly authoritative or an empty file.
     */
    public boolean databaseHasRows() {
        File dbFile = new File(plugin.getDataFolder(), DB_FILENAME);
        if (!dbFile.exists()) return false;
        // If migration marker exists, treat as not ready
        File marker = new File(plugin.getDataFolder(), MIGRATION_MARKER);
        if (marker.exists()) return false;

        try {
            ensureDatabase();
            if (dbConnection == null) return false;
            try (PreparedStatement stmt = dbConnection.prepareStatement("SELECT 1 FROM player_jobs LIMIT 1")) {
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("SqliteStorage: failed to query database rows: " + e.getMessage());
            return false;
        }
    }

    private boolean isMigrationInProgress() {
        File marker = new File(plugin.getDataFolder(), MIGRATION_MARKER);
        return marker.exists();
    }

    /**
     * Create up to two rotating backups of the existing database.
     */
    public void createDatabaseBackups() {
        try {
            File dbFile = new File(plugin.getDataFolder(), DB_FILENAME);
            if (!dbFile.exists()) return;

            Path bak1 = new File(plugin.getDataFolder(), BACKUP_1).toPath();
            Path bak2 = new File(plugin.getDataFolder(), BACKUP_2).toPath();
            Path dbPath = dbFile.toPath();

            // Rotate backups: bak1 -> bak2
            if (Files.exists(bak1)) {
                Files.copy(bak1, bak2, StandardCopyOption.REPLACE_EXISTING);
            }
            // Copy current db to bak1
            Files.copy(dbPath, bak1, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("SqliteStorage: created database backup (rotated, keeping 2 backups)");
        } catch (IOException e) {
            plugin.getLogger().warning("SqliteStorage: failed to create database backup: " + e.getMessage());
        }
    }

    /**
     * Migrate player data from PDC (XP/money) and YAML leaderboard (levels) on command.
     * Can be called via /jobs migrate to populate the database.
     */
    public void migrateData() {
        // Atomic migration: write to a temporary DB file, backup leaderboard.yml, then move into place.
        plugin.getLogger().info("SqliteStorage: starting atomic migration from PDC and YAML...");

        Map<UUID, me.usainsrht.ujobs.models.PlayerLeaderboardData> leaderboardCache = plugin.getLeaderboardManager().getLeaderboardPlayerCache();

        // Build comprehensive UUID list: leaderboard.yml keys, online players, and all playerdata files across worlds
        java.util.Set<UUID> allUuids = new java.util.HashSet<>(leaderboardCache.keySet());
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) allUuids.add(p.getUniqueId());

        // Scan all worlds' playerdata directories
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            File pdDir = new File(world.getWorldFolder(), "playerdata");
            if (!pdDir.exists() || !pdDir.isDirectory()) continue;
            File[] files = pdDir.listFiles((dir, name) -> name.endsWith(".dat"));
            if (files == null) continue;
            for (File f : files) {
                String name = f.getName();
                try {
                    String uuidStr = name.substring(0, name.length() - 4);
                    UUID u = UUID.fromString(uuidStr);
                    allUuids.add(u);
                } catch (Exception ignored) {}
            }
        }

        // Map of playerdata files by UUID for faster lookup
        Map<UUID, File> playerDataFiles = new HashMap<>();
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            File pdDir = new File(world.getWorldFolder(), "playerdata");
            if (!pdDir.exists() || !pdDir.isDirectory()) continue;
            File[] files = pdDir.listFiles((dir, name) -> name.endsWith(".dat"));
            if (files == null) continue;
            for (File f : files) {
                String name = f.getName();
                try {
                    String uuidStr = name.substring(0, name.length() - 4);
                    UUID u = UUID.fromString(uuidStr);
                    playerDataFiles.put(u, f);
                } catch (Exception ignored) {}
            }
        }

        File tmpFile = new File(plugin.getDataFolder(), TEMP_DB_FILENAME);
        File markerFile = new File(plugin.getDataFolder(), MIGRATION_MARKER);

        // Backup leaderboard.yml before attempting migration
        backupLeaderboardYml();

        // Create migration marker
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            Files.write(markerFile.toPath(), new byte[0]);
        } catch (IOException e) {
            plugin.getLogger().severe("SqliteStorage: could not create migration marker: " + e.getMessage());
            return;
        }

        // Diagnostic: log counts before pre-reading PDC
        plugin.getLogger().info("SqliteStorage: migrateData preparing migration: allUuids=" + allUuids.size() + ", leaderboardCache=" + leaderboardCache.size() + ", playerDataFiles=" + playerDataFiles.size());
        if (!allUuids.isEmpty()) {
            int sample = Math.min(10, allUuids.size());
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (UUID u : allUuids) {
                if (i++ >= sample) break;
                sb.append(u.toString()).append(",");
            }
            plugin.getLogger().info("SqliteStorage: migrateData sample uuids=" + sb.toString());
        }

        // Pre-read PDC data in parallel to speed up IO-bound NBT parsing
        ConcurrentHashMap<UUID, PlayerJobData> pdcMap = new ConcurrentHashMap<>();
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        try {
            for (UUID uuid : allUuids) {
                ex.submit(() -> {
                    try {
                        PlayerJobData pjd = null;
                        // Prefer reading playerdata file if present
                        File pdFile = playerDataFiles.get(uuid);
                        if (pdFile != null && pdFile.exists()) {
                            pjd = readPdcFromPlayerDataFile(pdFile, uuid);
                        } else {
                            // If player is online, fetch PDC on the main thread
                            org.bukkit.entity.Player player = Bukkit.getPlayer(uuid);
                            if (player != null && player.isOnline()) {
                                try {
                                    java.util.concurrent.Future<PlayerJobData> f = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                                        PersistentDataContainer pdc = player.getPersistentDataContainer();
                                        if (pdc.has(TAG_JOBS_DATA)) {
                                            return deserializePdc(uuid, pdc.get(TAG_JOBS_DATA, PersistentDataType.TAG_CONTAINER));
                                        }
                                        return null;
                                    });
                                    pjd = f.get(10, TimeUnit.SECONDS);
                                } catch (Exception ignored) {
                                    // fallback to file read if possible
                                    if (pdFile != null && pdFile.exists()) pjd = readPdcFromPlayerDataFile(pdFile, uuid);
                                }
                            }
                        }
                        if (pjd != null) pdcMap.put(uuid, pjd);
                    } catch (Exception exx) {
                            plugin.getLogger().warning("SqliteStorage: failed to read PDC for " + uuid + ": " + exx.getMessage());
                    }
                });
            }
        } finally {
            ex.shutdown();
            try { ex.awaitTermination(10, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}
        }

        // Diagnostic: report how many PDC entries were successfully read
        try {
            plugin.getLogger().info("SqliteStorage: pre-read PDC entries for migration: " + pdcMap.size());
            if (!pdcMap.isEmpty()) {
                int sample = Math.min(10, pdcMap.size());
                int i = 0;
                for (UUID u : pdcMap.keySet()) {
                    if (i++ >= sample) break;
                    PlayerJobData pjd = pdcMap.get(u);
                    StringBuilder sb = new StringBuilder();
                    sb.append("SqliteStorage: sample PDC for ").append(u).append(": ");
                    for (Map.Entry<String, PlayerJobData.JobStats> je : pjd.getJobStats().entrySet()) {
                        sb.append("[").append(je.getKey()).append(" lvl=").append(je.getValue().getLevel())
                                .append(" exp=").append(je.getValue().getExp()).append("] ");
                    }
                    plugin.getLogger().info(sb.toString());
                }
            }
        } catch (Exception ignored) {}

        String tmpUrl = "jdbc:sqlite:" + tmpFile.getAbsolutePath();
        try (Connection tmpConn = DriverManager.getConnection(tmpUrl)) {
            try (Statement stmt = tmpConn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS player_jobs (" +
                        "uuid TEXT NOT NULL," +
                        "job_id TEXT NOT NULL," +
                        "level INTEGER DEFAULT 0," +
                        "exp REAL DEFAULT 0," +
                        "PRIMARY KEY (uuid, job_id))");
                // PRAGMA tuning for bulk-insert speed on temp DB
                try {
                    stmt.execute("PRAGMA journal_mode = WAL");
                    stmt.execute("PRAGMA synchronous = OFF");
                    stmt.execute("PRAGMA temp_store = MEMORY");
                    stmt.execute("PRAGMA cache_size = 10000");
                } catch (SQLException ignored) {}
            }

            tmpConn.setAutoCommit(false);

            try (PreparedStatement insertStmt = tmpConn.prepareStatement(
                    "INSERT INTO player_jobs (uuid, job_id, level, exp) VALUES (?, ?, ?, ?)") ) {
                final int BATCH_SIZE = 1000;
                int batchCount = 0;
                int inserted = 0;
                for (UUID uuid : allUuids) {
                    me.usainsrht.ujobs.models.PlayerLeaderboardData leaderboardData = leaderboardCache.get(uuid);
                    PlayerJobData pdcData = pdcMap.get(uuid);

                    // Diagnostic per-UUID: report whether PDC was read and how many nonzero-exp entries it contains
                    try {
                        if (pdcData == null) {
                            plugin.getLogger().fine("SqliteStorage: migrateData - no PDC read for " + uuid);
                        } else {
                            int nonZero = 0;
                            for (Map.Entry<String, PlayerJobData.JobStats> je : pdcData.getJobStats().entrySet()) {
                                if (je.getValue().getExp() > 0.0) nonZero++;
                            }
                            if (nonZero > 0) {
                                plugin.getLogger().info("SqliteStorage: migrateData - PDC for " + uuid + " has " + nonZero + " nonzero-exp job(s)");
                            } else {
                                plugin.getLogger().fine("SqliteStorage: migrateData - PDC for " + uuid + " had no nonzero-exp jobs");
                            }
                        }
                    } catch (Exception ignored) {}

                    if (leaderboardData != null) {
                        for (Map.Entry<me.usainsrht.ujobs.models.Job, me.usainsrht.ujobs.models.PlayerLeaderboardData.LeaderboardStats> leaderboardEntry : leaderboardData.getLeaderboardStats().entrySet()) {
                            me.usainsrht.ujobs.models.Job job = leaderboardEntry.getKey();
                            int level = leaderboardEntry.getValue().getLevel();
                            double exp = 0;
                            if (pdcData != null && pdcData.hasJobStats(job.getId())) {
                                exp = pdcData.getJobStats(job.getId()).getExp();
                            }
                            insertStmt.setString(1, uuid.toString());
                            insertStmt.setString(2, job.getId());
                            insertStmt.setInt(3, level);
                            insertStmt.setDouble(4, exp);
                            insertStmt.addBatch();
                            inserted++;
                            batchCount++;
                            if (batchCount >= BATCH_SIZE) {
                                insertStmt.executeBatch();
                                tmpConn.commit();
                                batchCount = 0;
                            }
                        }
                    } else if (pdcData != null) {
                        for (Map.Entry<String, PlayerJobData.JobStats> jobEntry : pdcData.getJobStats().entrySet()) {
                            String jobId = jobEntry.getKey();
                            PlayerJobData.JobStats stats = jobEntry.getValue();
                            insertStmt.setString(1, uuid.toString());
                            insertStmt.setString(2, jobId);
                            insertStmt.setInt(3, stats.getLevel());
                            insertStmt.setDouble(4, stats.getExp());
                            insertStmt.addBatch();
                            inserted++;
                            batchCount++;
                            if (batchCount >= BATCH_SIZE) {
                                insertStmt.executeBatch();
                                tmpConn.commit();
                                batchCount = 0;
                            }
                        }
                    }
                }
                plugin.getLogger().info("SqliteStorage: migrateData prepared " + inserted + " insert rows (batched)");
                if (batchCount > 0) {
                    insertStmt.executeBatch();
                }
            }

            tmpConn.commit();
        } catch (SQLException e) {
            plugin.getLogger().severe("SqliteStorage: migration failed: " + e.getMessage());
            try { Files.deleteIfExists(markerFile.toPath()); } catch (IOException ignored) {}
            return;
        }

        // Move temp DB into place atomically (rotate existing backups first)
        try {
            // rotate backups of existing DB
            createDatabaseBackups();

            File dbFile = new File(plugin.getDataFolder(), DB_FILENAME);
            File target = new File(plugin.getDataFolder(), DB_FILENAME);
            // Replace existing db if present
            Files.move(tmpFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            plugin.getLogger().info("SqliteStorage: migration complete, " + leaderboardCache.size() + " players migrated to database");
        } catch (IOException e) {
            plugin.getLogger().severe("SqliteStorage: failed to move migrated DB into place: " + e.getMessage());
        } finally {
            try { Files.deleteIfExists(markerFile.toPath()); } catch (IOException ignored) {}
        }
    }

    /**
     * Backup leaderboard.yml (rotate one backup) prior to migration.
     */
    private void backupLeaderboardYml() {
        try {
            File leaderboardFile = new File(plugin.getDataFolder(), "leaderboard.yml");
            if (!leaderboardFile.exists()) return;
            File bak1 = new File(plugin.getDataFolder(), "leaderboard.yml.bak1");
            File bak2 = new File(plugin.getDataFolder(), "leaderboard.yml.bak2");
            Path pBak1 = bak1.toPath();
            Path pBak2 = bak2.toPath();
            Path pYml = leaderboardFile.toPath();

            if (Files.exists(pBak1)) {
                Files.copy(pBak1, pBak2, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(pYml, pBak1, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("SqliteStorage: backed up leaderboard.yml (rotated, keeping 2 backups)");
        } catch (IOException e) {
            plugin.getLogger().warning("SqliteStorage: failed to backup leaderboard.yml: " + e.getMessage());
        }
    }

    /**
     * If a migration marker exists on startup, attempt to recover: either finish moving the tmp DB into place,
     * or restore from backups if tmp is missing.
     */
    public void checkAndRecoverMigration() {
        File marker = new File(plugin.getDataFolder(), MIGRATION_MARKER);
        if (!marker.exists()) return;

        plugin.getLogger().warning("SqliteStorage: detected incomplete migration marker; attempting recovery...");
        File tmp = new File(plugin.getDataFolder(), TEMP_DB_FILENAME);
        File db = new File(plugin.getDataFolder(), DB_FILENAME);

        try {
            if (tmp.exists()) {
                // Try to atomically move tmp into place
                try {
                    Files.move(tmp.toPath(), db.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    plugin.getLogger().info("SqliteStorage: recovered migration by moving temp DB into place");
                    Files.deleteIfExists(marker.toPath());
                    return;
                } catch (IOException e) {
                    plugin.getLogger().warning("SqliteStorage: failed to move temp DB into place: " + e.getMessage());
                }
            }

            // If tmp not present or move failed, attempt to restore from backup1
            File bak1 = new File(plugin.getDataFolder(), BACKUP_1);
            if (bak1.exists()) {
                Files.copy(bak1.toPath(), db.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("SqliteStorage: restored database from backup1 after failed migration");
                Files.deleteIfExists(marker.toPath());
                return;
            }

            plugin.getLogger().severe("SqliteStorage: incomplete migration detected but no temp DB or backups available; manual intervention required");
        } catch (IOException e) {
            plugin.getLogger().severe("SqliteStorage: error during migration recovery: " + e.getMessage());
        }
    }

    /**
     * Preload offline playerdata (.dat) files into the in-memory cache so copied playerdata
     * are recognized on plugin startup even before players join.
     */
    public void preloadOfflinePdcToCache() {
        try {
            int loaded = 0;
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                File pdDir = new File(world.getWorldFolder(), "playerdata");
                if (!pdDir.exists() || !pdDir.isDirectory()) continue;
                File[] files = pdDir.listFiles((dir, name) -> name.endsWith(".dat"));
                if (files == null) continue;
                for (File f : files) {
                    try {
                        String name = f.getName();
                        String uuidStr = name.substring(0, name.length() - 4);
                        UUID uuid = UUID.fromString(uuidStr);
                        // If DB exists, skip (DB is authoritative)
                        if (databaseExists()) continue;
                        // If leaderboard already has an entry for this player, skip so leaderboard.yml remains authoritative
                        if (plugin.getLeaderboardManager() != null && plugin.getLeaderboardManager().getLeaderboardPlayerCache().containsKey(uuid)) continue;
                        PlayerJobData pjd = readPdcFromPlayerDataFile(f, uuid);
                        if (pjd != null && !pjd.getJobStats().isEmpty()) {
                            // store in preloadCache; do not write to leaderboard.yml until player logs in
                            PlayerJobData existing = preloadCache.get(uuid);
                            if (existing == null) {
                                preloadCache.put(uuid, pjd);
                            } else {
                                for (Map.Entry<String, PlayerJobData.JobStats> e : pjd.getJobStats().entrySet()) {
                                    existing.setJobStats(e.getKey(), e.getValue());
                                }
                            }
                            loaded++;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            plugin.getLogger().info("SqliteStorage: preloaded " + loaded + " offline playerdata entries into cache");
        } catch (Exception e) {
            plugin.getLogger().warning("SqliteStorage: failed to preload offline playerdata: " + e.getMessage());
        }
    }

    /**
     * Migrate player data from PDC (XP/money) and YAML leaderboard (levels) on startup.
     */
    private void migrateFromPdcAndYaml() {
        migrateData();
    }

    /**
     * Read PDC data from offline player (for XP/money recovery).
     */
    private PlayerJobData getPdcDataForOfflinePlayer(UUID uuid) {
        try {
            // Try to read PDC from player data file in world folder
            File playerDataFile = new File(Bukkit.getWorld("world").getWorldFolder(), "playerdata/" + uuid + ".dat");
            if (!playerDataFile.exists()) {
                return null;
            }

            // For now, we'll try to get it from cache if player is online
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                PersistentDataContainer pdc = player.getPersistentDataContainer();
                if (pdc.has(TAG_JOBS_DATA)) {
                    return deserializePdc(uuid, pdc.get(TAG_JOBS_DATA, PersistentDataType.TAG_CONTAINER));
                }
            }
            // If player is offline, attempt to read their playerdata NBT file and extract the PDC tag
            PlayerJobData offline = readPdcFromPlayerDataFile(playerDataFile, uuid);
            if (offline != null) return offline;
        } catch (Exception e) {
            plugin.getLogger().warning("SqliteStorage: could not read PDC for offline player " + uuid);
        }

        return null;
    }

    /**
     * Attempt to read the player's .dat NBT file using reflection (multiple possible NBT reader classes),
     * convert to a string representation and parse for the jobs PDC tag content.
     */
    private PlayerJobData readPdcFromPlayerDataFile(File playerDataFile, UUID uuid) {
        try {
            plugin.getLogger().info("SqliteStorage: attempting to read playerdata file " + playerDataFile.getAbsolutePath() + " (size=" + playerDataFile.length() + ") for " + uuid);
            // Try to invoke various possible NBT reader methods via reflection
            Object compoundTag = null;
            Exception lastEx = null;
            // Candidate NBT reader class names (include modern, legacy, and craftbukkit wrappers)
            java.util.List<String> candidates = new java.util.ArrayList<>();
            candidates.add("net.minecraft.nbt.NbtIo");
            candidates.add("net.minecraft.nbt.NBTCompressedStreamTools");
            candidates.add("net.minecraft.nbt.CompressedStreamTools");
            candidates.add("net.minecraft.nbt.NBTCompressedStreamTools");

            // Try server-versioned NMS and CraftBukkit package variants (e.g., org.bukkit.craftbukkit.v1_20_R1...)
            try {
                String pkg = Bukkit.getServer().getClass().getPackage().getName();
                plugin.getLogger().info("SqliteStorage: server class package=" + pkg);
                String version = pkg.substring(pkg.lastIndexOf('.') + 1);
                candidates.add("net.minecraft.server." + version + ".NBTCompressedStreamTools");
                candidates.add("org.bukkit.craftbukkit." + version + ".NBTCompressedStreamTools");
                candidates.add("org.bukkit.craftbukkit." + version + ".nbt.NBTCompressedStreamTools");
                candidates.add("org.bukkit.craftbukkit." + version + ".util.NBTCompressedStreamTools");
                candidates.add("net.minecraft.server." + version + ".nbt.NBTCompressedStreamTools");
            } catch (Exception ignored) {}

            String[] readerClasses = candidates.toArray(new String[0]);
            
            // Avoid logging the same NBT-reader failure repeatedly
            final boolean[] nbtWarningLogged = {false};
            for (String clsName : readerClasses) {
                try {
                    Class<?> cls = Class.forName(clsName);
                    plugin.getLogger().fine("SqliteStorage: found NBT reader class " + clsName + " for file " + playerDataFile.getName());
                    try {
                        java.lang.reflect.Method m = cls.getMethod("readCompressed", java.io.InputStream.class);
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(playerDataFile)) {
                            compoundTag = m.invoke(null, fis);
                        }
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // try alternative method names
                        try {
                            java.lang.reflect.Method m2 = cls.getMethod("a", java.io.InputStream.class);
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(playerDataFile)) {
                                compoundTag = m2.invoke(null, fis);
                            }
                            break;
                        } catch (NoSuchMethodException ignored2) {
                            // continue
                        }
                    }
                } catch (ClassNotFoundException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
                    lastEx = e;
                }
            }

            if (compoundTag == null) {
                // Log per-file exception details to help diagnose why NBT reading failed
                if (lastEx != null) {
                    plugin.getLogger().info("SqliteStorage: NBT reader failed for file " + playerDataFile.getName() + ": " + lastEx.getClass().getName() + ": " + lastEx.getMessage());
                } else {
                    plugin.getLogger().info("SqliteStorage: NBT reader returned no compound tag for " + playerDataFile.getName());
                }
                if (!nbtReaderWarned) {
                    plugin.getLogger().warning("SqliteStorage: NBT reader not found or failed (first warn): " + (lastEx == null ? "unknown" : lastEx.getMessage()));
                    nbtReaderWarned = true;
                }
                // As a last-resort, attempt a raw scan of the compressed .dat file contents for our 'ujobs' tag text
                try {
                    plugin.getLogger().fine("SqliteStorage: attempting raw scan of playerdata file for " + uuid);
                    String raw = null;
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(playerDataFile)) {
                        try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(fis)) {
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[4096];
                            int r;
                            while ((r = gis.read(buf)) != -1) baos.write(buf, 0, r);
                            raw = new String(baos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                        } catch (Exception gzEx) {
                            // try raw inflate (some MC server variants use different compression)
                            try (java.io.FileInputStream fis2 = new java.io.FileInputStream(playerDataFile);
                                 java.util.zip.InflaterInputStream iis = new java.util.zip.InflaterInputStream(fis2)) {
                                java.io.ByteArrayOutputStream baos2 = new java.io.ByteArrayOutputStream();
                                byte[] buf2 = new byte[4096];
                                int r2;
                                while ((r2 = iis.read(buf2)) != -1) baos2.write(buf2, 0, r2);
                                raw = new String(baos2.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                            } catch (Exception infEx) {
                                // Last attempt: read raw bytes and convert to string
                                try (java.io.FileInputStream fis3 = new java.io.FileInputStream(playerDataFile)) {
                                    java.io.ByteArrayOutputStream baos3 = new java.io.ByteArrayOutputStream();
                                    byte[] buf3 = new byte[4096];
                                    int r3;
                                    while ((r3 = fis3.read(buf3)) != -1) baos3.write(buf3, 0, r3);
                                    raw = new String(baos3.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
                                } catch (Exception eee) {
                                    raw = null;
                                }
                            }
                        }
                    }

                    if (raw != null) {
                        // Try to find 'ujobs' compound or any job-like compound in the raw text
                        java.util.regex.Pattern p = java.util.regex.Pattern.compile("ujobs[:]?jobs_data\\s*[:=]\\s*\\{([^}]*)\\}", java.util.regex.Pattern.DOTALL);
                        java.util.regex.Matcher m = p.matcher(raw);
                        String innerRaw = null;
                        if (m.find()) {
                            innerRaw = m.group(1);
                        } else {
                            // fallback: try to find any 'ujobs' compound
                            p = java.util.regex.Pattern.compile("ujobs\\s*[:=]\\s*\\{([^}]*)\\}", java.util.regex.Pattern.DOTALL);
                            m = p.matcher(raw);
                            if (m.find()) innerRaw = m.group(1);
                        }

                        if (innerRaw != null) {
                            PlayerJobData result = new me.usainsrht.ujobs.storage.PDCStorage(plugin).parseFromText(uuid, innerRaw);
                            plugin.getLogger().info("SqliteStorage: raw-scan recovered PDC for " + uuid + " with jobs=" + result.getJobStats().size());
                            return result;
                        }
                    }
                } catch (Exception exx) {
                    plugin.getLogger().warning("SqliteStorage: raw scan failed for " + uuid + ": " + exx.getMessage());
                }

                // As a stronger fallback, attempt a proper in-process NBT parse to locate the ujobs compound
                try {
                    String inner = NbtUtils.extractUjobsInner(playerDataFile);
                    if (inner != null) {
                        PlayerJobData result = new me.usainsrht.ujobs.storage.PDCStorage(plugin).parseFromText(uuid, inner);
                        plugin.getLogger().info("SqliteStorage: NBT parse recovered PDC for " + uuid + " with jobs=" + result.getJobStats().size());
                        // If parsed jobs exist but all exp values are zero, log the raw ujobs map to help diagnose keys
                        boolean anyNonZero = false;
                        for (PlayerJobData.JobStats js : result.getJobStats().values()) {
                            if (js.getExp() > 0.0) { anyNonZero = true; break; }
                        }
                        if (!anyNonZero) {
                            try {
                                java.util.Map<String,Object> raw = NbtUtils.extractUjobsMap(playerDataFile);
                                if (raw != null) {
                                    plugin.getLogger().info("SqliteStorage: raw ujobs map for " + uuid + " -> " + raw.toString());
                                }
                            } catch (Exception e) {
                                // ignore
                            }
                        }
                        return result;
                    }
                } catch (Exception exx) {
                    plugin.getLogger().warning("SqliteStorage: NBT extract failed for " + uuid + ": " + exx.getMessage());
                }

                return null;
            }

            // Call toString() on the compound tag and parse out our PDC content
            String tagString = compoundTag.toString();
            if (tagString == null || !tagString.contains("ujobs")) {
                // Log a short diagnostic sample to help debug formats
                String sample = tagString == null ? "<null>" : tagString.substring(0, Math.min(200, tagString.length()));
                plugin.getLogger().info("SqliteStorage: compoundTag present but no 'ujobs' key in playerdata " + playerDataFile.getName() + ", sample=" + sample.replaceAll("\n", "\\n"));
            }

            // Find the 'ujobs:jobs_data' or 'ujobs' key content
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("ujobs[:]?jobs_data\\s*[:=]\\s*\\{([^}]*)\\}", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(tagString);
            if (!m.find()) {
                // try just 'ujobs' compound
                p = java.util.regex.Pattern.compile("ujobs\\s*[:=]\\s*\\{([^}]*)\\}", java.util.regex.Pattern.DOTALL);
                m = p.matcher(tagString);
                if (!m.find()) return null;
            }

            String inner = m.group(1);
            PlayerJobData result = new me.usainsrht.ujobs.storage.PDCStorage(plugin).parseFromText(uuid, inner);
            return result;
        } catch (Exception e) {
            plugin.getLogger().warning("SqliteStorage: failed to parse playerdata NBT for " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    private PlayerJobData deserializePdc(UUID uuid, PersistentDataContainer pdc) {
        PlayerJobData playerJobData = new PlayerJobData(uuid);

        for (NamespacedKey jobKey : pdc.getKeys()) {
            if (!pdc.has(jobKey, PersistentDataType.TAG_CONTAINER)) {
                continue;
            }
            PersistentDataContainer jobContainer = pdc.get(jobKey, PersistentDataType.TAG_CONTAINER);
            int level = jobContainer.getOrDefault(TAG_LEVEL, PersistentDataType.INTEGER, -1);
            double exp = 0.0;
            double totalMoney = 0.0;

            // try modern exp key first, then legacy 'xp'
            if (jobContainer.has(TAG_EXP, PersistentDataType.DOUBLE)) {
                exp = jobContainer.get(TAG_EXP, PersistentDataType.DOUBLE);
            } else if (jobContainer.has(TAG_XP, PersistentDataType.DOUBLE)) {
                exp = jobContainer.get(TAG_XP, PersistentDataType.DOUBLE);
            }

            if (jobContainer.has(TAG_TOTAL_MONEY, PersistentDataType.DOUBLE)) {
                totalMoney = jobContainer.get(TAG_TOTAL_MONEY, PersistentDataType.DOUBLE);
            }

            String jobId = jobKey.getKey();
            // If level is missing but we have total EXP, compute level from job config
            me.usainsrht.ujobs.models.Job job = plugin.getJobManager().getJobs().get(jobId);
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

            playerJobData.setJobStats(jobId, new PlayerJobData.JobStats(level, exp, totalMoney));
        }

        return playerJobData;
    }



    @Override
    public void save() {
        plugin.getLogger().info("SqliteStorage: saving online players' cached data");
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            save(player.getUniqueId());
        }
    }

    @Override
    public void save(UUID uuid) {
        PlayerJobData playerJobData = cache.get(uuid);
        if (playerJobData == null) return;

        // Only save to database if it exists
        if (databaseExists()) {
            // Only persist if data has changed
            if (playerJobData.isDirty()) {
                saveToDB(uuid, playerJobData);
            } else {
                plugin.getLogger().fine("SqliteStorage: skipping save for " + uuid + " (not dirty)");
            }
        }
        // If database does not exist, do not call saveToPdc (method does not exist)
    }

    private void saveToDB(UUID uuid, PlayerJobData playerJobData) {
        try {
            ensureDatabase();
            if (dbConnection == null) return;
            dbConnection.setAutoCommit(false);

            try (PreparedStatement deleteStmt = dbConnection.prepareStatement("DELETE FROM player_jobs WHERE uuid = ?")) {
                deleteStmt.setString(1, uuid.toString());
                deleteStmt.executeUpdate();
            }

            try (PreparedStatement insertStmt = dbConnection.prepareStatement(
                    "INSERT INTO player_jobs (uuid, job_id, level, exp) VALUES (?, ?, ?, ?)") ) {
                for (Map.Entry<String, PlayerJobData.JobStats> entry : playerJobData.getJobStats().entrySet()) {
                    insertStmt.setString(1, uuid.toString());
                    insertStmt.setString(2, entry.getKey());
                    insertStmt.setInt(3, entry.getValue().getLevel());
                    insertStmt.setDouble(4, entry.getValue().getExp());
                    insertStmt.addBatch();
                }
                insertStmt.executeBatch();
            }

            dbConnection.commit();
            // mark clean after successful save
            playerJobData.clearDirty();
        } catch (SQLException e) {
            try {
                if (dbConnection != null) dbConnection.rollback();
            } catch (SQLException ex) {
                plugin.getLogger().severe("SqliteStorage: failed to rollback after save error: " + ex.getMessage());
            }
            plugin.getLogger().severe("SqliteStorage: failed to save data for " + uuid + ": " + e.getMessage());
        } finally {
            try {
                if (dbConnection != null) dbConnection.setAutoCommit(true);
            } catch (SQLException ignored) {}
        }
    }

    @Override
    public CompletableFuture<PlayerJobData> load(UUID uuid) {
        CompletableFuture<PlayerJobData> future = new CompletableFuture<>();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                // Ensure DB connection is initialized when a DB file exists
                if (databaseExists()) {
                    ensureDatabase();
                }
                PlayerJobData playerJobData = loadFromDB(uuid);
                
                // Only overwrite cache if database has data, or if cache is empty.
                // This preserves leaderboard.yml data in cache before /jobs migrate is run.
                if (!playerJobData.getJobStats().isEmpty() || !cache.containsKey(uuid)) {
                        // mark as clean because this data came from persistent storage
                        playerJobData.clearDirty();
                        cache.put(uuid, playerJobData);
                        // Update leaderboard caches from DB-loaded player data so GUI can show entries
                        try {
                            if (plugin.getLeaderboardManager() != null) {
                                for (Map.Entry<String, PlayerJobData.JobStats> e : playerJobData.getJobStats().entrySet()) {
                                    String jobId = e.getKey();
                                    int level = e.getValue().getLevel();
                                    me.usainsrht.ujobs.models.Job job = plugin.getJobManager().getJobs().get(jobId);
                                    if (job == null) continue;
                                    me.usainsrht.ujobs.models.PlayerLeaderboardData pld = plugin.getLeaderboardManager().getLeaderboardPlayerCache().computeIfAbsent(uuid, me.usainsrht.ujobs.models.PlayerLeaderboardData::new);
                                    // Set display name if available from server cache
                                    try {
                                        String name = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
                                        if (name != null) pld.displayName = name;
                                    } catch (Exception ignored) {}
                                    int pos = -1;
                                    me.usainsrht.ujobs.models.PlayerLeaderboardData.LeaderboardStats existing = pld.getLeaderboardStats().get(job);
                                    if (existing != null) pos = existing.getPosition();
                                    pld.getLeaderboardStats().put(job, new me.usainsrht.ujobs.models.PlayerLeaderboardData.LeaderboardStats(pos, level));

                                    UUID[] rebuilt = plugin.getLeaderboardManager().createLeaderboardFromCache(job);
                                    if (rebuilt != null) plugin.getLeaderboardManager().getLeaderboardJobCache().put(job, rebuilt);
                                }
                                plugin.getLogger().info("SqliteStorage: updated leaderboard caches from DB for " + uuid);
                            }
                        } catch (Exception exx) {
                            plugin.getLogger().warning("SqliteStorage: failed to update leaderboard caches for " + uuid + ": " + exx.getMessage());
                        }
                        future.complete(playerJobData);
                        // Log detailed load for debugging: job count and per-job exp/level
                        StringBuilder sb = new StringBuilder();
                        sb.append("SqliteStorage: loaded data for ").append(uuid).append(" (jobs=")
                            .append(playerJobData.getJobStats().size()).append("):\n");
                        for (Map.Entry<String, PlayerJobData.JobStats> e : playerJobData.getJobStats().entrySet()) {
                        sb.append(" - ").append(e.getKey()).append(": level=").append(e.getValue().getLevel())
                            .append(", exp=").append(e.getValue().getExp()).append("\n");
                        }
                        plugin.getLogger().info(sb.toString());
                } else {
                    // Database is empty but cache has data from leaderboard.yml
                    PlayerJobData existing = cache.get(uuid);
                    // If the player is online, attempt to read their PDC and merge into the cached data
                    org.bukkit.entity.Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        try {
                            PersistentDataContainer pdc = player.getPersistentDataContainer();
                            if (pdc.has(TAG_JOBS_DATA, PersistentDataType.TAG_CONTAINER)) {
                                PlayerJobData deserialized = deserializePdc(uuid, pdc.get(TAG_JOBS_DATA, PersistentDataType.TAG_CONTAINER));
                                // Merge: only fill missing job stats from PDC so leaderboard.yml remains authoritative
                                for (Map.Entry<String, PlayerJobData.JobStats> e : deserialized.getJobStats().entrySet()) {
                                    String jobId = e.getKey();
                                    if (!existing.hasJobStats(jobId)) {
                                        existing.setJobStats(jobId, e.getValue());
                                    }
                                }
                                plugin.getLogger().info("SqliteStorage: merged online PDC into cached data for " + uuid + " (only filled missing jobs)");
                            }
                        } catch (Exception ex) {
                            plugin.getLogger().warning("SqliteStorage: failed to merge PDC for " + uuid + ": " + ex.getMessage());
                        }
                    }
                    future.complete(existing);
                    plugin.getLogger().info("SqliteStorage: cache hit for " + uuid + " (database empty, using leaderboard.yml data)");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("SqliteStorage: failed to load data for " + uuid + ": " + e.getMessage());
                future.cancel(false);
            }
        });

        return future;
    }

    private PlayerJobData loadFromDB(UUID uuid) throws SQLException {
        // If database doesn't exist yet, return empty data
        if (!databaseExists()) {
            return new PlayerJobData(uuid);
        }
        PlayerJobData playerJobData = new PlayerJobData(uuid);

        try (PreparedStatement stmt = dbConnection.prepareStatement(
                "SELECT job_id, level, exp FROM player_jobs WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String jobId = rs.getString("job_id");
                    int level = rs.getInt("level");
                    double exp = rs.getDouble("exp");
                    playerJobData.setJobStats(jobId, new PlayerJobData.JobStats(level, exp, 0.0));
                }
            }
        }

        return playerJobData;
    }

    @Override
    public boolean isCached(UUID uuid) {
        return cache.containsKey(uuid);
    }

    @Override
    public PlayerJobData getCached(UUID uuid) {
        return cache.getOrDefault(uuid, null);
    }

    @Override
    public void set(UUID uuid, PlayerJobData playerJobData) {
        cache.put(uuid, playerJobData);
        // Only persist to database if it exists (migration has been run)
        if (databaseExists()) {
            saveToDB(uuid, playerJobData);
        }
    }

    @Override
    public void remove(UUID uuid) {
        removeFromCache(uuid);
        // Only remove from database if it exists
        if (!databaseExists() || dbConnection == null) {
            return;
        }
        try (PreparedStatement stmt = dbConnection.prepareStatement("DELETE FROM player_jobs WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
            plugin.getLogger().info("SqliteStorage: removed data for " + uuid);
        } catch (SQLException e) {
            plugin.getLogger().severe("SqliteStorage: failed to remove data for " + uuid);
        }
    }

    @Override
    public void removeFromCache(UUID uuid) {
        // Only remove from cache if database exists.
        // Before migration, preserve YAML-loaded cache across player quits.
        if (databaseExists()) {
            cache.remove(uuid);
        }
    }

    public void closeDatabase() {
        try {
            if (dbConnection != null && !dbConnection.isClosed()) {
                dbConnection.close();
                plugin.getLogger().info("SqliteStorage: database connection closed");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("SqliteStorage: error closing database: " + e.getMessage());
        }
    }

    /**
     * Transfer any preloaded offline PDC data for the given UUID into the active cache.
     * Returns the PlayerJobData now present in the active cache (either transferred or existing).
     */
    public PlayerJobData transferPreloadedToCache(UUID uuid) {
        PlayerJobData pre = preloadCache.remove(uuid);
        if (pre == null) return cache.get(uuid);
        PlayerJobData existing = cache.get(uuid);
        if (existing == null) {
            cache.put(uuid, pre);
            return pre;
        }
        // Merge: prefer higher level, then higher exp when levels equal.
        for (Map.Entry<String, PlayerJobData.JobStats> e : pre.getJobStats().entrySet()) {
            String jobId = e.getKey();
            PlayerJobData.JobStats preStats = e.getValue();
            if (!existing.hasJobStats(jobId)) {
                existing.setJobStats(jobId, preStats);
                continue;
            }
            PlayerJobData.JobStats existStats = existing.getJobStats(jobId);
            if (preStats.getLevel() > existStats.getLevel()) {
                existing.setJobStats(jobId, preStats);
            } else if (preStats.getLevel() == existStats.getLevel() && preStats.getExp() > existStats.getExp()) {
                existing.setJobStats(jobId, preStats);
            }
        }
        return existing;
    }

    public boolean hasPreloaded(UUID uuid) {
        return preloadCache.containsKey(uuid);
    }

    /**
     * Ensure the in-memory cache contains the best available data for a player when they join.
     * Runs synchronously on the main thread and merges leaderboard cache, preloaded PDC and
     * the player's live PDC without overwriting newer/higher values.
     */
    public void ensureCachePopulatedOnJoin(UUID uuid, org.bukkit.entity.Player player) {
        // Do nothing if DB is authoritative
        if (databaseExists()) return;

        PlayerJobData current = cache.get(uuid);

        // If leaderboard manager has data for this player, ensure cache includes it
        me.usainsrht.ujobs.models.PlayerLeaderboardData lbd = plugin.getLeaderboardManager().getLeaderboardPlayerCache().get(uuid);
        if (lbd != null) {
            if (current == null) current = new PlayerJobData(uuid);
            for (Map.Entry<me.usainsrht.ujobs.models.Job, me.usainsrht.ujobs.models.PlayerLeaderboardData.LeaderboardStats> statEntry : lbd.getLeaderboardStats().entrySet()) {
                String jobId = statEntry.getKey().getId();
                int level = statEntry.getValue().getLevel();
                // only set if missing or lower
                if (!current.hasJobStats(jobId)) {
                    current.setJobStats(jobId, new PlayerJobData.JobStats(level, 0.0, 0.0));
                }
            }
        }

        // Merge any preloaded offline PDC
        if (preloadCache.containsKey(uuid)) {
            PlayerJobData pre = preloadCache.remove(uuid);
            if (current == null) current = pre;
            else {
                for (Map.Entry<String, PlayerJobData.JobStats> e : pre.getJobStats().entrySet()) {
                    String jobId = e.getKey();
                    PlayerJobData.JobStats preStats = e.getValue();
                    if (!current.hasJobStats(jobId)) {
                        current.setJobStats(jobId, preStats);
                    } else {
                        PlayerJobData.JobStats existStats = current.getJobStats(jobId);
                        if (preStats.getLevel() > existStats.getLevel() || (preStats.getLevel() == existStats.getLevel() && preStats.getExp() > existStats.getExp())) {
                            current.setJobStats(jobId, preStats);
                        }
                    }
                }
            }
        }

        // Merge this player's live PDC (if present) without overwriting superior cached values
        try {
            if (player != null && player.isOnline()) {
                org.bukkit.persistence.PersistentDataContainer pdc = player.getPersistentDataContainer();
                org.bukkit.persistence.PersistentDataType<org.bukkit.persistence.PersistentDataContainer, org.bukkit.persistence.PersistentDataContainer> pdt = org.bukkit.persistence.PersistentDataType.TAG_CONTAINER;
                if (pdc.has(TAG_JOBS_DATA, pdt)) {
                    PlayerJobData deserialized = deserializePdc(uuid, pdc.get(TAG_JOBS_DATA, pdt));
                    if (current == null) current = deserialized;
                    else {
                        for (Map.Entry<String, PlayerJobData.JobStats> e : deserialized.getJobStats().entrySet()) {
                            String jobId = e.getKey();
                            PlayerJobData.JobStats pStats = e.getValue();
                            if (!current.hasJobStats(jobId)) {
                                current.setJobStats(jobId, pStats);
                            } else {
                                PlayerJobData.JobStats existStats = current.getJobStats(jobId);
                                if (pStats.getLevel() > existStats.getLevel() || (pStats.getLevel() == existStats.getLevel() && pStats.getExp() > existStats.getExp())) {
                                    current.setJobStats(jobId, pStats);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (current != null) cache.put(uuid, current);
    }
}
