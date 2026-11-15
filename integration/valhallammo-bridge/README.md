# Crop & Kettle - ValhallaMMO bridge

This optional Paper/Purpur plugin connects the Crop & Kettle datapack to
[schisophrenic/ValhallaMMO](https://github.com/schisophrenic/ValhallaMMO).
It reads the datapack's existing per-player scoreboard counters without rewriting
crop, scythe, cooking-pot, cutting-board, or mixing-bowl functions.

## Install

1. Install Crop & Kettle as a datapack and your ValhallaMMO fork as a plugin.
2. Build the bridge from the repository root using Java 21+ and Maven:
   mvn -f integration/valhallammo-bridge/pom.xml package
   Alternatively download the JAR artifact from the CnK ValhallaMMO Bridge GitHub Actions workflow.
3. Put the resulting cnk-valhallammo-bridge-1.0.0.jar in the server plugins folder; restart.
4. Edit plugins/CnKValhallaBridge/config.yml to tune or disable individual rewards; restart again.

## Rewards

- cnk.statistic.crops_harvested -> FARMING (48 XP by default)
- cnk.statistic.food_cooked -> ALCHEMY (48 XP by default)
- cnk.statistic.food_cut -> ALCHEMY (8 XP by default)
- cnk.statistic.food_mixed -> ALCHEMY (24 XP by default)
- cnk.statistic.drinks_bottled -> ALCHEMY (16 XP by default)

ValhallaMMO has no built-in COOKING skill. Cooking actions use ALCHEMY by
default but can be assigned to a configurable custom skill.

The bridge calls ValhallaMMO Skill.addEXP with SKILL_ACTION, allowing the main
plugin to apply its ordinary permission, skill, creative-mode, experience, and
limit rules. Historical counters are ignored on startup and player join.
Counter resets are not treated as new actions. To limit huge counter jumps,
at most max-events-per-poll new actions per metric are rewarded each interval.

The plugin relies on ValhallaMMO's public Java API, discovered at runtime, so
it does not need the premium ValhallaMMO artifact in Maven. An incompatible API
causes the bridge to disable itself with an error instead of changing stats.

This bridge adds skill experience only. It does not override Crop & Kettle's
custom food nutrition, datapack effects, recipes, loot tables, or crop growth.
Food-class compatibility belongs in the ValhallaMMO food handling layer.
