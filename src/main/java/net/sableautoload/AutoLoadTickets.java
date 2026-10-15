package net.sableautoload;

import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
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
            SubLevelLoadingTicketType.create(
                    ResourceLocation.fromNamespaceAndPath(SableAutoLoad.MODID, "auto"),
                    Codec.of(Encoder.<UUID>error("session-only ticket"), Decoder.<UUID>error("session-only ticket")));

    private AutoLoadTickets() {
    }

    /**
     * Forces class initialisation, which registers {@link #AUTO} in Sable's ticket type registry.
     * This must happen before saved sub-level ticket data is read, so Sable recognizes this type and
     * safely drops legacy records through the deliberately failing decoder.
     */
    public static void init() {
        // Intentional no-op: loading this class performs the registration.
    }
}
