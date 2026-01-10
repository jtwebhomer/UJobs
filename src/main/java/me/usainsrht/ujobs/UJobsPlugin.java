package me.usainsrht.ujobs;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import lombok.Getter;
import me.usainsrht.ujobs.commands.MainCommand;
import me.usainsrht.ujobs.listeners.InventoryListener;
import me.usainsrht.ujobs.listeners.JoinListener;
import me.usainsrht.ujobs.listeners.QuitListener;
import me.usainsrht.ujobs.listeners.SaveListener;
import me.usainsrht.ujobs.listeners.job_actions.*;
import me.usainsrht.ujobs.managers.*;
import me.usainsrht.ujobs.models.BuiltInActions;
import me.usainsrht.ujobs.placeholders.JobPlaceholders;
import me.usainsrht.ujobs.storage.SqliteStorage;
import me.usainsrht.ujobs.storage.Storage;
import me.usainsrht.ujobs.yaml.YamlCommand;
import me.usainsrht.ujobs.yaml.YamlMessage;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Level;

@Getter
public final class UJobsPlugin extends JavaPlugin {

    public static UJobsPlugin instance;
    private MiniMessage miniMessage;
    private Economy economy;
    private Storage storage;

    // Managers
    private ConfigManager configManager;
    private JobManager jobManager;
    private LeaderboardManager leaderboardManager;
    private JobGUIManager guiManager;
    private BossBarManager bossBarManager;
    private NPCManager npcManager;

    @Override
    public void onEnable() {
        instance = this;

        // Initialize Adventure API
        this.miniMessage = MiniMessage.miniMessage();

        setupEconomy();

        this.storage = new SqliteStorage(this);
        // Create rotating backups of the database on startup (keeps up to 2 backups)
        if (this.storage instanceof SqliteStorage sqlite) {
            sqlite.createDatabaseBackups();
            // Attempt to recover any incomplete migrations from previous crashes
            sqlite.checkAndRecoverMigration();
        }

        initializeManagers();

        // After managers and leaderboard are loaded, preload offline PDC for players missing leaderboard entries
        if (this.storage instanceof SqliteStorage sqlite) {
            sqlite.preloadOfflinePdcToCache();
        }

        registerEventsAndCommands();

        // Start leaderboard calculation timer
        //currently disabled
        startLeaderboardTimer();

        registerPlaceholders();

        getLogger().info("UJobs has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        // Flush all online players' XP to cache before saving
        getServer().getOnlinePlayers().forEach(player -> {
            if (storage != null) {
                storage.save(player.getUniqueId());
            }
        });

        // Save all data: persist online players' data and write their exp to leaderboard.yml
        if (storage != null) {
            storage.save();
        }

        if (leaderboardManager != null) {
            // Persist per-online-player exp/levels to leaderboard.yml to avoid global overwrite
            getServer().getOnlinePlayers().forEach(p -> {
                try {
                    leaderboardManager.saveExpForPlayer(p.getUniqueId());
                } catch (Exception ignored) {}
            });
        }
        if (configManager != null) configManager.saveLeaderboard(); // write leaderboard.yml once

        // Close database if open (after saving)
        if (storage instanceof SqliteStorage sqliteStorage) {
            sqliteStorage.closeDatabase();
        }

        // Cancel all boss bars
        if (bossBarManager != null) {
            bossBarManager.removeAllBossBars();
        }

        getLogger().info("UJobs has been disabled!");
    }

    private void initializeManagers() {
        try {
            this.configManager = new ConfigManager(this);
            this.jobManager = new JobManager(this);
            this.leaderboardManager = new LeaderboardManager(this);
            this.bossBarManager = new BossBarManager(this);
            this.guiManager = new JobGUIManager(this);
            this.npcManager = new NPCManager(this);

            // Load configurations
            configManager.loadConfigs();
            jobManager.loadJobs();
            leaderboardManager.load(configManager.getLeaderboardConfig());

            getLogger().info("All managers initialized successfully!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize managers", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerEventsAndCommands() {
        // Register event listeners
        getServer().getPluginManager().registerEvents(new InventoryListener(), this);
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        getServer().getPluginManager().registerEvents(new QuitListener(this), this);
        getServer().getPluginManager().registerEvents(new SaveListener(this), this);

        // job listeners
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Material.BREAK)) {
            getServer().getPluginManager().registerEvents(new BreakListener(jobManager), this);
        }
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Material.PLACE)) {
            getServer().getPluginManager().registerEvents(new PlaceListener(jobManager), this);
        }
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Entity.TAME)) {
            getServer().getPluginManager().registerEvents(new TameListener(jobManager), this);
        }
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Entity.BREED)) {
            getServer().getPluginManager().registerEvents(new BreedListener(jobManager), this);
        }
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Entity.KILL)) {
            getServer().getPluginManager().registerEvents(new KillListener(jobManager), this);
        }
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Special.ENCHANT)) {
            getServer().getPluginManager().registerEvents(new EnchantListener(jobManager), this);
        }
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Material.FISH)) {
            getServer().getPluginManager().registerEvents(new FishListener(jobManager), this);
        }
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Special.RAID)) {
            getServer().getPluginManager().registerEvents(new RaidListener(jobManager), this);
        }
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Special.GENERATE_LOOT)) {
            getServer().getPluginManager().registerEvents(new LootGenerateListener(jobManager), this);
        }
        // Register TravelListener unconditionally to listen for block movement
        getServer().getPluginManager().registerEvents(new TravelListener(jobManager), this);
        getLogger().info("TravelListener registered for travel job tracking");
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Material.TRADE)) {
            getServer().getPluginManager().registerEvents(new TradeListener(jobManager), this);
        }
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Material.ANVIL_MERGE)) {
            getServer().getPluginManager().registerEvents(new AnvilMergeListener(jobManager), this);
        }
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Material.CRAFT)) {
            getServer().getPluginManager().registerEvents(new CraftListener(jobManager), this);
        }
        if (jobManager.getActionJobMap().containsKey(BuiltInActions.Material.HARVEST)) {
            getServer().getPluginManager().registerEvents(new HarvestListener(jobManager), this);
        }

        LifecycleEventManager<Plugin> manager = this.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            YamlCommand mainCommand = YamlCommand.builder()
                    .name(getConfig().getString("command.name", "ujobs"))
                    .description(getConfig().getString("command.description", "UJobs main command"))
                    .aliases(getConfig().getStringList("command.aliases"))
                    .permission(getConfig().getString("command.permission"))
                    .permissionMessage(new YamlMessage(getConfig().get("command.permission_message")))
                    .build();
            commands.register(getPluginMeta(), MainCommand.create(this, mainCommand), mainCommand.getDescription(), mainCommand.getAliases());
        });
    }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp == null) {
                return;
            }
            economy = rsp.getProvider();
        }
    }

    private void registerPlaceholders() {
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new JobPlaceholders(this).register();
        }
    }

    private void startLeaderboardTimer() {
        new BukkitRunnable() {
            @Override
            public void run() {
                //leaderboardManager.calculateLeaderboard();
            }
        }.runTaskTimerAsynchronously(this, 20L * 60 * 2, 20L * 60 * 60); // Every hour after 2 minute delay
    }


}
