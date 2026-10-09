package com.foundations.cluster.playtest;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Existing Node mod lifecycle; no additional mod ID, registries or network channel. */
@EventBusSubscriber(modid = "foundations_cluster_node")
public final class PlaytestBootstrap {
    private static final FoundationsPlaytest SERVICE = new FoundationsPlaytest();
    private PlaytestBootstrap() {}
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        SERVICE.registerCommands(event.getDispatcher());
    }
}
