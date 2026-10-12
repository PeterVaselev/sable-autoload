package net.sableautoload;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Keeps Sable sub-levels around online players force-loaded through this mod's own ticket type.
 * <p>
 * Every {@link #SCAN_INTERVAL_TICKS} server ticks, for each player:
 * <ul>
 *     <li>auto-load tickets this mod holds for that player are released once the sub-level left the
 *     release box (radius + {@link #HYSTERESIS_CHUNKS} chunks) - hysteresis against flapping;</li>
 *     <li>sub-levels intersecting the load box (radius chunks) are sorted by distance to the player
 *     and the nearest ones, up to {@code sableAutoLoadMax}, get an auto-load ticket.</li>
 * </ul>
 * A ticket is never handed out while the world under the sub-level is still ungenerated: waking a
 * sub-level makes Sable block-load every chunk of its bounding box (see
 * {@code PhysicsChunkTicketManager#inhabitChunk}, which calls
 * {@code level.getChunk(x, z, ChunkStatus.FULL, true)}), and doing that outside of the pregenerated
 * area generates terrain on the server thread and has crashed this server before.
 */
public final class AutoLoadManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How often (in server ticks) the ticket set is recomputed. */
    private static final int SCAN_INTERVAL_TICKS = 20;

    /** How often (in server ticks) the per player state is logged. */
    private static final int LOG_INTERVAL_TICKS = 600;

    /** Extra chunks a sub-level may travel away before its auto-load ticket is released. */
    private static final int HYSTERESIS_CHUNKS = 16;

    /** Sable expands a sub-level bounding box by one block before working out the chunks it needs. */
    private static final double FOOTPRINT_MARGIN = 1.0;

    /**
     * Safety valve for the worldgen guard: a footprint bigger than this many chunks is treated as
     * "not generated" instead of scanning it. Real sub-levels are a small handful of chunks wide.
     */
    private static final long MAX_FOOTPRINT_CHUNKS = 4096L;

    /** Player UUID -> sub-level UUIDs this mod keeps loaded for that player (fast bookkeeping). */
    private final Map<UUID, Set<UUID>> tracked = new HashMap<>();

    /** Player UUID -> amount of sub-levels reported in the last log line. */
    private final Map<UUID, Integer> lastLoggedAmount = new HashMap<>();

    /** Set once an oversized footprint was reported, so the warning cannot spam the log. */
    private boolean warnedOversizedFootprint;

    private int tickCounter;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();
        this.tickCounter++;

        if (this.tickCounter % SCAN_INTERVAL_TICKS == 0) {
            for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    this.updatePlayer(server, player);
                } catch (final Exception e) {
                    LOGGER.error("Sable AutoLoad: failed to update auto-load tickets for {}", player.getScoreboardName(), e);
                }
            }
        }

        if (this.tickCounter % LOG_INTERVAL_TICKS == 0) {
            this.logStatus(server);
        }
    }

    /**
     * Recomputes the auto-load tickets for a single player.
     */
    private void updatePlayer(MinecraftServer server, ServerPlayer player) {
        final UUID playerId = player.getUUID();
        // Sub-level containers are per dimension, so a player only ever loads sub-levels of their own level.
        final ServerLevel level = player.serverLevel();
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        final int radiusChunks = SableAutoLoadGameRules.radiusChunks(server);
        if (radiusChunks <= 0) {
            // Disabled: drop everything this mod holds for the player.
            this.releaseAllFor(server, playerId);
            return;
        }

        final int max = SableAutoLoadGameRules.maxSubLevels(server);
        final Set<UUID> mine = this.tracked.computeIfAbsent(playerId, key -> new HashSet<>());

        final double px = player.getX();
        final double py = player.getY();
        final double pz = player.getZ();
        final double releaseRange = (radiusChunks + HYSTERESIS_CHUNKS) * 16.0;
        final double releaseRangeSq = releaseRange * releaseRange;

        // Everything inside the (wider) release box is considered "still wanted": only sub-levels
        // that left it lose their ticket, which keeps ships from flapping in and out at the border.
        final Set<UUID> stillNear = new HashSet<>();
        for (final SubLevel sub : container.queryIntersecting(box(px, py, pz, releaseRange))) {
            stillNear.add(sub.getUniqueId());
        }

        // Release pass. The container's own ticket data is authoritative and gives us the loaded
        // ServerSubLevel instances needed to remove a ticket.
        final List<ReleaseTarget> toRelease = new ArrayList<>();
        final Set<UUID> heldNow = new HashSet<>();
        for (final Map.Entry<ServerSubLevel, Set<SubLevelLoadingTicket<?>>> entry : container.collectForceLoadTickets().entrySet()) {
            final ServerSubLevel sub = entry.getKey();
            final UUID subId = sub.getUniqueId();
            UUID key = null;
            for (final SubLevelLoadingTicket<?> ticket : entry.getValue()) {
                if (isAutoTicket(ticket) && playerId.equals(ticket.key())) {
                    key = playerId;
                    break;
                }
            }
            if (key == null) {
                continue;
            }
            heldNow.add(subId);
            if (!stillNear.contains(subId) || distanceSq(sub, px, py, pz) > releaseRangeSq) {
                toRelease.add(new ReleaseTarget(sub, key));
            }
        }

        // Forget bookkeeping for sub-levels this mod no longer holds a ticket for.
        mine.retainAll(heldNow);

        for (final ReleaseTarget target : toRelease) {
            if (container.removeForceLoadTicket(target.subLevel(), AutoLoadTickets.AUTO, target.key())) {
                mine.remove(target.subLevel().getUniqueId());
            }
        }

        if (max <= 0) {
            return;
        }

        // Add pass: nearest candidates first, capped at sableAutoLoadMax sub-levels per player.
        final double loadRange = radiusChunks * 16.0;
        final List<Candidate> candidates = new ArrayList<>();
        for (final SubLevel sub : container.queryIntersecting(box(px, py, pz, loadRange))) {
            if (!(sub instanceof final ServerSubLevel serverSub)) {
                continue;
            }
            if (mine.contains(serverSub.getUniqueId())) {
                continue;
            }
            candidates.add(new Candidate(serverSub, distanceSq(serverSub, px, py, pz)));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distanceSq));

        final int heldAtStart = mine.size();
        int added = 0;
        for (final Candidate candidate : candidates) {
            if (heldAtStart + added >= max) {
                break;
            }
            final ServerSubLevel sub = candidate.subLevel();
            final Vector3dc position = position(sub);
            if (position == null) {
                continue;
            }
            // Worldgen guard: never wake a sub-level whose chunks have not been generated yet.
            if (!isFootprintGenerated(level, sub, position)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Sable AutoLoad: skipped sub-level {} for {} - world not generated yet",
                            sub.getUniqueId(), player.getScoreboardName());
                }
                continue;
            }
            if (container.addForceLoadTicket(sub, AutoLoadTickets.AUTO, playerId)) {
                mine.add(sub.getUniqueId());
                added++;
            }
        }
    }

    /**
     * Releases every auto-load ticket this mod holds for the given player, in all dimensions.
     */
    private void releaseAllFor(MinecraftServer server, UUID playerId) {
        final int removed = this.releaseMatching(server, ticket -> playerId.equals(ticket.key()));
        this.tracked.remove(playerId);
        this.lastLoggedAmount.remove(playerId);
        if (removed > 0) {
            LOGGER.info("Sable AutoLoad: released {} auto-load ticket(s) for player {} (auto-loading disabled or player gone)", removed, playerId);
        }
    }

    /**
     * Releases every auto-load ticket whose key matches the predicate, in all dimensions of the server.
     */
    private int releaseMatching(MinecraftServer server, Predicate<SubLevelLoadingTicket<?>> match) {
        int removed = 0;
        for (final ServerLevel level : server.getAllLevels()) {
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            final List<ReleaseTarget> targets = new ArrayList<>();
            for (final Map.Entry<ServerSubLevel, Set<SubLevelLoadingTicket<?>>> entry : container.collectForceLoadTickets().entrySet()) {
                for (final SubLevelLoadingTicket<?> ticket : entry.getValue()) {
                    if (isAutoTicket(ticket) && match.test(ticket) && ticket.key() instanceof final UUID key) {
                        targets.add(new ReleaseTarget(entry.getKey(), key));
                        break;
                    }
                }
            }
            for (final ReleaseTarget target : targets) {
                if (container.removeForceLoadTicket(target.subLevel(), AutoLoadTickets.AUTO, target.key())) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Clears the tickets persisted by previous sessions so a restart always starts from a clean slate.
     * <p>
     * Sable force-loads every sub-level that has persisted ticket data while the level is being
     * constructed ({@code ServerSubLevelContainer#initialize}), which happens before either server
     * lifecycle event, so this also serves as the recovery path for a session that was started with
     * stale auto-load tickets. Both events are handled because dimensions created between them bring
     * up their own container and their own saved tickets.
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        this.clearPersistedTickets(event.getServer(), "server starting");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        this.tracked.clear();
        this.lastLoggedAmount.clear();
        this.tickCounter = 0;
        this.warnedOversizedFootprint = false;
        this.clearPersistedTickets(event.getServer(), "server started");
    }

    private void clearPersistedTickets(MinecraftServer server, String phase) {
        final int removed = this.releaseMatching(server, ticket -> true);
        if (removed > 0) {
            LOGGER.info("Sable AutoLoad: cleared {} auto-load ticket(s) persisted by a previous session ({})", removed, phase);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        this.tracked.clear();
        this.lastLoggedAmount.clear();
        this.tickCounter = 0;
        this.warnedOversizedFootprint = false;
    }

    /**
     * A player that leaves takes their tickets with them, otherwise ships would stay loaded for nobody.
     */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof final ServerPlayer player)) {
            return;
        }
        final MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        final UUID playerId = player.getUUID();
        final int removed = this.releaseMatching(server, ticket -> playerId.equals(ticket.key()));
        this.tracked.remove(playerId);
        this.lastLoggedAmount.remove(playerId);
        LOGGER.info("Sable AutoLoad: released {} auto-load ticket(s) for {} (logged out)", removed, player.getScoreboardName());
    }

    private void logStatus(MinecraftServer server) {
        final int radiusChunks = SableAutoLoadGameRules.radiusChunks(server);
        final int max = SableAutoLoadGameRules.maxSubLevels(server);
        final Set<UUID> online = new HashSet<>();

        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            final UUID playerId = player.getUUID();
            online.add(playerId);
            final Set<UUID> mine = this.tracked.get(playerId);
            final int amount = mine == null ? 0 : mine.size();
            final Integer previous = this.lastLoggedAmount.put(playerId, amount);
            if (previous == null || previous != amount) {
                LOGGER.info("Sable AutoLoad: keeping {} sub-level(s) loaded for {} (radius {} chunks, max {})",
                        amount, player.getScoreboardName(), radiusChunks, max);
            }
        }

        this.lastLoggedAmount.keySet().retainAll(online);
    }

    /** Does the ticket belong to this mod? The type is a singleton, so equality on the record is exact. */
    private static boolean isAutoTicket(SubLevelLoadingTicket<?> ticket) {
        return AutoLoadTickets.AUTO.equals(ticket.type());
    }

    /**
     * Checks whether the world data a force-load ticket would need is already present.
     * <p>
     * Sable keeps a sub-level loaded by taking vanilla chunk tickets for the chunks covering the
     * sub-level's bounding box and then block-loading each of them
     * ({@code level.getChunk(x, z, ChunkStatus.FULL, true)} in {@code PhysicsChunkTicketManager}).
     * Those chunks therefore have to exist already, or the ticket would generate terrain on the
     * server thread. The check mirrors Sable's own footprint (bounding box expanded by one block) and
     * samples nothing: every chunk of it has to be a non-blocking hit.
     */
    private boolean isFootprintGenerated(ServerLevel level, SubLevel sub, Vector3dc position) {
        // The chunk the sub-level is standing on is the holding chunk Sable stores it in.
        if (!isChunkGenerated(level, position.x(), position.z())) {
            return false;
        }

        final BoundingBox3dc bounds = sub.boundingBox();
        if (bounds == null) {
            return true;
        }
        final double minX = bounds.minX();
        final double minZ = bounds.minZ();
        final double maxX = bounds.maxX();
        final double maxZ = bounds.maxZ();
        if (!Double.isFinite(minX) || !Double.isFinite(minZ) || !Double.isFinite(maxX) || !Double.isFinite(maxZ)) {
            // Broken geometry: nothing sensible to check, the position chunk check above stands.
            return true;
        }
        if (isDefaultBounds(minX, minZ, maxX, maxZ)) {
            // Bounding box not computed yet, Sable will not ask for more than the chunk above either.
            return true;
        }

        final int minChunkX = SectionPos.blockToSectionCoord(Math.min(minX, maxX) - FOOTPRINT_MARGIN);
        final int maxChunkX = SectionPos.blockToSectionCoord(Math.max(minX, maxX) + FOOTPRINT_MARGIN);
        final int minChunkZ = SectionPos.blockToSectionCoord(Math.min(minZ, maxZ) - FOOTPRINT_MARGIN);
        final int maxChunkZ = SectionPos.blockToSectionCoord(Math.max(minZ, maxZ) + FOOTPRINT_MARGIN);

        final long width = (long) maxChunkX - minChunkX + 1L;
        final long depth = (long) maxChunkZ - minChunkZ + 1L;
        if (width <= 0L || depth <= 0L || width * depth > MAX_FOOTPRINT_CHUNKS) {
            if (!this.warnedOversizedFootprint) {
                this.warnedOversizedFootprint = true;
                LOGGER.warn("Sable AutoLoad: refusing to auto-load a sub-level with an implausible footprint ({} x {} chunks)",
                        width, depth);
            }
            return false;
        }

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!isChunkGenerated(level, chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@code SubLevel} starts out with an all-zero bounding box before the plot bounds are known. */
    private static boolean isDefaultBounds(double minX, double minZ, double maxX, double maxZ) {
        return minX == 0.0 && minZ == 0.0 && maxX == 0.0 && maxZ == 0.0;
    }

    /**
     * Non-blocking check whether the given chunk is fully generated.
     */
    private static boolean isChunkGenerated(ServerLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
    }

    private static boolean isChunkGenerated(ServerLevel level, double x, double z) {
        return isChunkGenerated(level, SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
    }

    private static Vector3dc position(SubLevel sub) {
        final Pose3dc pose = sub.logicalPose();
        return pose == null ? null : pose.position();
    }

    private static double distanceSq(SubLevel sub, double x, double y, double z) {
        final Vector3dc position = position(sub);
        if (position == null) {
            return Double.MAX_VALUE;
        }
        final double dx = position.x() - x;
        final double dy = position.y() - y;
        final double dz = position.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static BoundingBox3d box(double x, double y, double z, double radius) {
        return new BoundingBox3d(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
    }

    private record Candidate(ServerSubLevel subLevel, double distanceSq) {
    }

    private record ReleaseTarget(ServerSubLevel subLevel, UUID key) {
    }
}
