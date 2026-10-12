package net.sableautoload;

import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * The force-load ticket type owned by this mod.
 * <p>
 * The ticket key is the UUID of the player the sub-level is kept loaded for.
 */
public final class AutoLoadTickets {

    /** Ticket type {@code sableautoload:auto}. */
    public static final SubLevelLoadingTicketType<UUID> AUTO =
            SubLevelLoadingTicketType.create(ResourceLocation.fromNamespaceAndPath(SableAutoLoad.MODID, "auto"), UUIDUtil.CODEC);

    private AutoLoadTickets() {
    }

    /**
     * Forces class initialisation, which registers {@link #AUTO} in Sable's ticket type registry.
     * This must happen before any sub-level ticket data is read from disk, otherwise Sable cannot
     * resolve the persisted {@code sableautoload:auto} type by name.
     */
    public static void init() {
        // Intentional no-op: loading this class performs the registration.
    }
}
