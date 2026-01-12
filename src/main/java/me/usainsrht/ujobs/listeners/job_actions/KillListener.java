package me.usainsrht.ujobs.listeners.job_actions;

import me.usainsrht.ujobs.managers.JobManager;
import me.usainsrht.ujobs.models.BuiltInActions;
import me.usainsrht.ujobs.models.Job;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class KillListener implements Listener {

    JobManager jobManager;

    public KillListener(JobManager jobManager) {
        this.jobManager = jobManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDamageByEntityEvent e) {
        Player player = null;
        if (e.getDamager() instanceof Player p) {
            player = p;
        } else if (e.getDamager() instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p2) player = p2;
        }

        if (player == null) return;

        if (jobManager.shouldIgnore(player)) return;

        if (!(e.getEntity() instanceof Damageable damageable)) return;

        if (e.getFinalDamage() >= damageable.getHealth()) {
            if (jobManager.getActionJobMap().containsKey(BuiltInActions.Entity.KILL)) {
                for (Job job : jobManager.getJobsWithAction(BuiltInActions.Entity.KILL)) {
                    jobManager.processAction(player, BuiltInActions.Entity.KILL, damageable.getType().name(), job, 1);
                }
            }
        }
    }
}
