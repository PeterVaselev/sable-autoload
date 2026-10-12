package net.sableautoload;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;

/**
 * Game rules controlling the automatic sub-level loading.
 */
public final class SableAutoLoadGameRules {

    /** Default radius in chunks around a player in which sub-levels are kept loaded. */
    public static final int DEFAULT_RADIUS_CHUNKS = 64;

    /** Default maximum amount of sub-levels kept loaded per player. */
    public static final int DEFAULT_MAX_SUB_LEVELS = 8;

    public static GameRules.Key<GameRules.IntegerValue> RULE_AUTO_LOAD_RADIUS;
    public static GameRules.Key<GameRules.IntegerValue> RULE_AUTO_LOAD_MAX;

    private SableAutoLoadGameRules() {
    }

    public static void register() {
        if (RULE_AUTO_LOAD_RADIUS != null) {
            return;
        }
        RULE_AUTO_LOAD_RADIUS = GameRules.register("sableAutoLoadRadius", GameRules.Category.MISC,
                GameRules.IntegerValue.create(DEFAULT_RADIUS_CHUNKS));
        RULE_AUTO_LOAD_MAX = GameRules.register("sableAutoLoadMax", GameRules.Category.MISC,
                GameRules.IntegerValue.create(DEFAULT_MAX_SUB_LEVELS));
    }

    /** Radius in chunks; {@code 0} disables the feature. */
    public static int radiusChunks(MinecraftServer server) {
        if (RULE_AUTO_LOAD_RADIUS == null) {
            return 0;
        }
        return Math.max(0, server.getGameRules().getInt(RULE_AUTO_LOAD_RADIUS));
    }

    /** Maximum amount of sub-levels per player; {@code 0} disables the feature. */
    public static int maxSubLevels(MinecraftServer server) {
        if (RULE_AUTO_LOAD_MAX == null) {
            return 0;
        }
        return Math.max(0, server.getGameRules().getInt(RULE_AUTO_LOAD_MAX));
    }
}
