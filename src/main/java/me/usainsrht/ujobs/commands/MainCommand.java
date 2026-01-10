package me.usainsrht.ujobs.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.usainsrht.ujobs.UJobsPlugin;
import me.usainsrht.ujobs.models.Job;
import me.usainsrht.ujobs.models.PlayerJobData;
import me.usainsrht.ujobs.storage.SqliteStorage;
import me.usainsrht.ujobs.utils.JobExpUtils;
import me.usainsrht.ujobs.utils.MessageUtil;
import me.usainsrht.ujobs.yaml.YamlCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class MainCommand {

    public static LiteralCommandNode<CommandSourceStack> create(UJobsPlugin plugin, YamlCommand yamlCommand) {
        return Commands.literal(yamlCommand.getName())
                .requires(context -> {
                    // Check if the player has the required permission
                    if (yamlCommand.getPermission() != null) {
                        return context.getSender().hasPermission(yamlCommand.getPermission());
                    }
                    return true;
                })
                .executes(context -> {
                    if (context.getSource().getSender() instanceof Player player) plugin.getGuiManager().openJobGUI(player);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("reload")
                        .requires(context -> context.getSender().hasPermission("ujobs.admin.reload"))
                        .executes(context -> {
                            plugin.getConfigManager().reload();
                            plugin.getJobManager().loadJobs();

                            MessageUtil.send(context.getSource().getSender(), plugin.getConfigManager().getMessage("reload"));

                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("migrate")
                        .requires(context -> context.getSender().hasPermission("ujobs.admin.migrate"))
                        .executes(context -> {
                            java.io.File dbFile = new java.io.File(plugin.getDataFolder(), "ujobs.db");
                            if (dbFile.exists()) {
                                context.getSource().getSender().sendMessage(Component.text("§6[UJobs] UJobs is already migrated!"));
                                return Command.SINGLE_SUCCESS;
                            }

                            context.getSource().getSender().sendMessage(Component.text("§6[UJobs] Starting migration from PDC and YAML to SQLite database..."));

                            if (plugin.getStorage() instanceof SqliteStorage sqliteStorage) {
                                // Run migration off the main server thread to avoid blocking
                                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                    try {
                                        sqliteStorage.migrateData();
                                    } catch (Exception e) {
                                        plugin.getLogger().severe("Migration failed: " + e.getMessage());
                                    }
                                    // Notify sender on the main thread when done
                                    Bukkit.getScheduler().runTask(plugin, () -> context.getSource().getSender().sendMessage(Component.text("§a[UJobs] Migration complete! All player data has been migrated to the database.")));
                                });
                            } else {
                                context.getSource().getSender().sendMessage(Component.text("§c[UJobs] Error: Storage is not using SQLite! Migration only works with SQLite storage."));
                                return -1;
                            }

                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("addexp")
                        .requires(context -> context.getSender().hasPermission("ujobs.admin.addexp"))
                        .executes(context -> {
                            context.getSource().getSender().sendMessage("Usage: /ujobs addexp <player> <job> <amount>");
                            return -1;
                        })
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(context -> {
                                    context.getSource().getSender().sendMessage("Usage: /ujobs addexp <player> <job> <amount>");
                                    return -1;
                                })
                                .then(Commands.argument("job", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            plugin.getJobManager().getJobs().keySet().forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            context.getSource().getSender().sendMessage("Usage: /ujobs addexp <player> <job> <amount>");
                                            return -1;
                                        })
                                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                                .executes(context -> {
                                                    final PlayerSelectorArgumentResolver targetResolver = context.getArgument("target", PlayerSelectorArgumentResolver.class);
                                                    final Player target = targetResolver.resolve(context.getSource()).getFirst();
                                                    String jobId = StringArgumentType.getString(context, "job");
                                                    double amount = DoubleArgumentType.getDouble(context, "amount");

                                                    if (plugin.getJobManager().getJobs().containsKey(jobId)) {
                                                        Job job = plugin.getJobManager().getJobs().get(jobId);
                                                        Job.ActionReward reward = new Job.ActionReward(amount, 0);
                                                        JobExpUtils.processJobExp(target, job, reward,1);
                                                        return Command.SINGLE_SUCCESS;
                                                    } else {
                                                        context.getSource().getSender().sendMessage("Job not found: " + jobId);
                                                        return -1;
                                                    }

                                                })
                                        )
                                )
                        )
                )
                .then(Commands.literal("setlevel")
                        .requires(context -> context.getSender().hasPermission("ujobs.admin.setlevel"))
                        .executes(context -> {
                            context.getSource().getSender().sendMessage("Usage: /ujobs setlevel <player> <job> <level>");
                            return -1;
                        })
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(context -> {
                                    context.getSource().getSender().sendMessage("Usage: /ujobs setlevel <player> <job> <level>");
                                    return -1;
                                })
                                .then(Commands.argument("job", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            plugin.getJobManager().getJobs().keySet().forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            context.getSource().getSender().sendMessage("Usage: /ujobs setlevel <player> <job> <level>");
                                            return -1;
                                        })
                                        .then(Commands.argument("level", IntegerArgumentType.integer(0))
                                                .executes(context -> {
                                                    final PlayerSelectorArgumentResolver targetResolver = context.getArgument("target", PlayerSelectorArgumentResolver.class);
                                                    final Player target = targetResolver.resolve(context.getSource()).getFirst();
                                                    String jobId = StringArgumentType.getString(context, "job");
                                                    int level = IntegerArgumentType.getInteger(context, "level");

                                                    if (plugin.getJobManager().getJobs().containsKey(jobId)) {
                                                        PlayerJobData playerJobData = plugin.getStorage().getCached(target.getUniqueId());
                                                        PlayerJobData.JobStats jobStats = playerJobData.getJobStats(jobId);
                                                        jobStats.setLevel(level);
                                                        jobStats.setExp(0);
                                                        return Command.SINGLE_SUCCESS;
                                                    } else {
                                                        context.getSource().getSender().sendMessage("Job not found: " + jobId);
                                                        return -1;
                                                    }

                                                })
                                        )
                                )
                        )
                )
                .build();
    }


}
