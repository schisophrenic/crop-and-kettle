package io.github.schisophrenic.cnk.valhalla;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/**
 * Reads Crop & Kettle's existing per-player statistics and grants ValhallaMMO EXP.
 * No datapack or item modifications are needed.
 */
public final class CropAndKettleValhallaBridge extends JavaPlugin implements Listener {
    private static final Map<String, String> STATISTICS = Map.of(
            "crops_harvested", "cnk.statistic.crops_harvested",
            "food_cooked", "cnk.statistic.food_cooked",
            "food_cut", "cnk.statistic.food_cut",
            "food_mixed", "cnk.statistic.food_mixed",
            "drinks_bottled", "cnk.statistic.drinks_bottled"
    );

    private final Map<UUID, Map<String, Integer>> previousScores = new HashMap<>();
    private final Map<String, Reward> rewards = new LinkedHashMap<>();
    private final Set<String> missingSkills = new HashSet<>();
    private Method getSkill;
    private Method addExperience;
    private Object skillActionReason;
    private int maxEventsPerPoll;
    private boolean loggedInvocationFailure;

    private record Reward(String objective, String skill, double experience) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!Bukkit.getPluginManager().isPluginEnabled("ValhallaMMO")) {
            getLogger().severe("ValhallaMMO is required. Disabling the bridge.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            Class<?> registry = Class.forName("me.athlaeos.valhallammo.skills.skills.SkillRegistry");
            Class<?> skill = Class.forName("me.athlaeos.valhallammo.skills.skills.Skill");
            Class<?> reason = Class.forName("me.athlaeos.valhallammo.event.PlayerSkillExperienceGainEvent$ExperienceGainReason");
            getSkill = registry.getMethod("getSkill", String.class);
            addExperience = skill.getMethod("addEXP", Player.class, double.class, boolean.class, reason);
            for (Object constant : reason.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals("SKILL_ACTION")) {
                    skillActionReason = constant;
                    break;
                }
            }
            if (skillActionReason == null) throw new NoSuchFieldException("SKILL_ACTION");
        } catch (ReflectiveOperationException exception) {
            getLogger().severe("Incompatible ValhallaMMO API; no player experience changed: " + exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        maxEventsPerPoll = Math.max(1, getConfig().getInt("max-events-per-poll", 256));
        for (Map.Entry<String, String> entry : STATISTICS.entrySet()) {
            String path = "rewards." + entry.getKey();
            double experience = getConfig().getDouble(path + ".experience", 0);
            String configuredSkill = getConfig().getString(path + ".skill", "");
            String skill = configuredSkill == null ? "" : configuredSkill.trim().toUpperCase(Locale.ROOT);
            if (getConfig().getBoolean(path + ".enabled", true)
                    && Double.isFinite(experience) && experience > 0 && !skill.isEmpty()) {
                rewards.put(entry.getKey(), new Reward(entry.getValue(), skill, experience));
            }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) baseline(player);
        long interval = Math.max(1L, getConfig().getLong("poll-interval-ticks", 20L));
        Bukkit.getScheduler().runTaskTimer(this, this::poll, interval, interval);
        getLogger().info("Tracking " + rewards.size() + " Crop & Kettle statistics for ValhallaMMO.");
    }

    @Override
    public void onDisable() {
        previousScores.clear();
        rewards.clear();
        missingSkills.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Never award past statistics when the player joins or the bridge starts.
        baseline(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        previousScores.remove(event.getPlayer().getUniqueId());
    }

    private void baseline(Player player) {
        Map<String, Integer> scores = new HashMap<>();
        for (Reward reward : rewards.values()) scores.put(reward.objective(), readScore(player, reward.objective()));
        previousScores.put(player.getUniqueId(), scores);
    }

    private int readScore(Player player, String objectiveName) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return 0;
        Scoreboard scoreboard = manager.getMainScoreboard();
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) return 0;
        Score score = objective.getScore(player.getName());
        return score.isScoreSet() ? score.getScore() : 0;
    }

    private void poll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, Integer> previous = previousScores.get(player.getUniqueId());
            if (previous == null) {
                baseline(player);
                continue;
            }
            for (Reward reward : rewards.values()) {
                int now = readScore(player, reward.objective());
                Integer before = previous.put(reward.objective(), now);
                if (before == null || now <= before) continue; // reset or wrapped counter
                long delta = (long) now - before;
                int count = (int) Math.min(delta, maxEventsPerPoll);
                grantExperience(player, reward.skill(), reward.experience() * count);
            }
        }
    }

    private void grantExperience(Player player, String skillName, double amount) {
        try {
            Object skill = getSkill.invoke(null, skillName);
            if (skill == null) {
                if (missingSkills.add(skillName)) {
                    getLogger().warning("ValhallaMMO skill '" + skillName + "' is unavailable; skipping its rewards.");
                }
                return;
            }
            addExperience.invoke(skill, player, amount, false, skillActionReason);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            if (!loggedInvocationFailure) {
                getLogger().severe("ValhallaMMO rejected a reward (subsequent errors suppressed): " + exception);
                loggedInvocationFailure = true;
            }
        }
    }
}
