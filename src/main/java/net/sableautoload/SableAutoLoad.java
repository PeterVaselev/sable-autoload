package net.sableautoload;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Sable AutoLoad: keeps Sable sub-levels (Create Aeronautics ships) force-loaded around players.
 * <p>
 * Server-side only. Clients without this mod can still join a server that runs it.
 */
@Mod(SableAutoLoad.MODID)
public class SableAutoLoad {

    public static final String MODID = "sableautoload";

    public static final Logger LOGGER = LogUtils.getLogger();

    public SableAutoLoad(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.register(new AutoLoadManager());
        LOGGER.info("Sable AutoLoad: initialized");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Register the ticket type before any world (and therefore any saved ticket data) is read.
            AutoLoadTickets.init();
            SableAutoLoadGameRules.register();
        });
    }
}
