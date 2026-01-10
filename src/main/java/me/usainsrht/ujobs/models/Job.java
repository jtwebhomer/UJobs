package me.usainsrht.ujobs.models;

import lombok.Getter;
import me.usainsrht.ujobs.utils.MathUtil;
import me.usainsrht.ujobs.yaml.YamlMessage;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
public class Job {
    private final String id;
    private Component name;
    private Material icon;
    private String levelEquation;
    private YamlMessage levelUpMessage;
    private List<String> levelUpCommands;
    private BossBarConfig bossBarConfig;
    private final Map<Action, Map<String, ActionReward>> actions;
    private final List<JobInfoLine> infoLines;

    public Job(String id, Component name, Material icon, String levelEquation,
               YamlMessage levelUpMessage, List<String> levelUpCommands, BossBarConfig bossBarConfig) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.levelEquation = levelEquation;
        this.levelUpMessage = levelUpMessage;
        this.levelUpCommands = levelUpCommands;
        this.bossBarConfig = bossBarConfig;
        this.actions = new HashMap<>();
        this.infoLines = new ArrayList<>();
    }

    public void update(Job otherJobInstance) {
        if (otherJobInstance == null || !this.id.equalsIgnoreCase(otherJobInstance.id)) return;

        this.name = otherJobInstance.name;
        this.icon = otherJobInstance.icon;
        this.levelEquation = otherJobInstance.levelEquation;
        this.levelUpCommands = otherJobInstance.levelUpCommands;
        this.levelUpMessage = otherJobInstance.levelUpMessage;
        this.bossBarConfig = otherJobInstance.bossBarConfig;
        this.actions.clear();
        this.actions.putAll(otherJobInstance.actions);
        this.infoLines.clear();
        this.infoLines.addAll(otherJobInstance.infoLines);
    }

    public void addAction(Action action, String value, ActionReward reward) {
        actions.computeIfAbsent(action, k -> new HashMap<>()).put(value, reward);
    }

    public ActionReward getActionReward(Action action, String value) {
        Map<String, ActionReward> actionMap = actions.get(action);
        return actionMap != null ? actionMap.get(value) : null;
    }

    /**
     * For actions that use numeric thresholds (like travel), find the largest
     * configured threshold that is <= the provided amount and return its reward.
     */
    public ActionReward getActionRewardForAmount(Action action, int amount) {
        Map<String, ActionReward> actionMap = actions.get(action);
        if (actionMap == null) return null;

        int best = -1;
        ActionReward bestReward = null;
        for (String key : actionMap.keySet()) {
            try {
                int threshold = Integer.parseInt(key);
                if (threshold <= amount && threshold > best) {
                    best = threshold;
                    bestReward = actionMap.get(key);
                }
            } catch (NumberFormatException ignored) {
                // non-numeric keys are ignored for amount-based lookups
            }
        }
        return bestReward;
    }

    /**
     * Return the numeric threshold key (largest <= amount) for the given action,
     * or -1 if none exists.
     */
    public int getBestThresholdForAmount(Action action, int amount) {
        Map<String, ActionReward> actionMap = actions.get(action);
        if (actionMap == null) return -1;

        int best = -1;
        for (String key : actionMap.keySet()) {
            try {
                int threshold = Integer.parseInt(key);
                if (threshold <= amount && threshold > best) {
                    best = threshold;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    public long calculateExpForLevel(int level) {
        String equation = levelEquation.replace("<level>", String.valueOf(level))
                .replace("<next_level>", String.valueOf(level + 1));

        // Simple equation evaluator for basic math
        try {
            return (long) evaluateExpression(equation);
        } catch (Exception e) {
            // Fallback to default calculation
            return (long) Math.pow(level + 1, 2) * 5;
        }
    }

    private double evaluateExpression(String expression) {
        try {
            return MathUtil.eval(expression);
        } catch (Exception e) {
            throw new RuntimeException("Invalid expression: " + expression, e);
        }
    }

    @Getter
    public static class BossBarConfig {
        private final String titleTemplate;
        private final String levelUpTemplate;
        private final BossBar.Color color;
        private final BossBar.Overlay overlay;

        public BossBarConfig(String titleTemplate, String levelUpTemplate,
                             BossBar.Color color, BossBar.Overlay overlay) {
            this.titleTemplate = titleTemplate;
            this.levelUpTemplate = levelUpTemplate;
            this.color = color;
            this.overlay = overlay;
        }

    }

    @Getter
    public static class ActionReward {
        private final double exp;
        private final double money;

        public ActionReward(double exp, double money) {
            this.exp = exp;
            this.money = money;
        }

    }
}