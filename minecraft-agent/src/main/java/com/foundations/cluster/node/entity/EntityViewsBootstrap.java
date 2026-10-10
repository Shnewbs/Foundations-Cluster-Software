package com.foundations.cluster.node.entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
/** Existing Node only: no new mod ID, registry, entity type or custom client channel. */
@EventBusSubscriber(modid="foundations_cluster_node")
public final class EntityViewsBootstrap {
    @SubscribeEvent public static void commands(RegisterCommandsEvent e){PairedEntityViews.registerCommands(e.getDispatcher());}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){PairedEntityViews.tickServer(e.getServer());}
    @SubscribeEvent public static void stop(ServerStoppingEvent e){PairedEntityViews.stopServer(e.getServer());}
}
