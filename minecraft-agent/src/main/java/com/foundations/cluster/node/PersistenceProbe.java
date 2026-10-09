package com.foundations.cluster.node;

import java.nio.charset.StandardCharsets;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Bounded, read-only world signature for transfer diagnostics. Never loads a missing chunk. */
final class PersistenceProbe {
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("foundations_interaction_status").requires(s->s.hasPermission(2))
            .executes(c->{c.getSource().sendSuccess(()->Component.literal(
                "FOUNDATIONS_INTERACTION_STATUS node="+FoundationsClusterNode.VERSION+
                " remoteSurvival=false stage=SPIKE_A_C reason=chunk_entity_and_player_recovery_gates_pending"),false);return 1;}));
        event.getDispatcher().register(Commands.literal("foundations_growth_status").requires(s->s.hasPermission(2))
            .then(Commands.argument("pos",BlockPosArgument.blockPos()).executes(c->{
                String report=FoundationsClusterNode.growthStatus(c.getSource().getLevel(),BlockPosArgument.getBlockPos(c,"pos"));
                c.getSource().sendSuccess(()->Component.literal(report),false);return 1;
            })));

        event.getDispatcher().register(Commands.literal("foundations_region_ready").requires(s->s.hasPermission(2))
            .then(Commands.argument("region",StringArgumentType.string()).executes(c->{
                String region=StringArgumentType.getString(c,"region");
                String report="FOUNDATIONS_REGION_READY "+FoundationsClusterNode.regionReady(region)+" region="+region;
                c.getSource().sendSuccess(()->Component.literal(report),false);return 1;
            })));

        event.getDispatcher().register(Commands.literal("foundations_region_probe").requires(s->s.hasPermission(2))
            .then(Commands.argument("from",BlockPosArgument.blockPos()).then(Commands.argument("to",BlockPosArgument.blockPos()).executes(c->{
                var from=BlockPosArgument.getBlockPos(c,"from");var to=BlockPosArgument.getBlockPos(c,"to");
                long dx=Math.abs((long)from.getX()-to.getX())+1,dy=Math.abs((long)from.getY()-to.getY())+1,dz=Math.abs((long)from.getZ()-to.getZ())+1;
                long volume=dx>8192||dy>8192||dz>8192?8193:dx*dy*dz;
                if(volume>8192||volume<1){c.getSource().sendFailure(Component.literal("Probe limit is 8192 blocks"));return 0;}
                var level=c.getSource().getLevel();int blocks=0,logs=0,leaves=0;
                try {
                    var hash=MessageDigest.getInstance("SHA-256");
                    for(BlockPos pos:BlockPos.betweenClosed(from,to)) {
                        if(!level.hasChunkAt(pos)){c.getSource().sendFailure(Component.literal("Probe area is not loaded"));return 0;}
                        var state=level.getBlockState(pos);
                        hash.update((pos.getX()+","+pos.getY()+","+pos.getZ()+":"+state.toString()+"\n").getBytes(StandardCharsets.UTF_8));
                        if(!state.isAir())blocks++;if(state.is(BlockTags.LOGS))logs++;if(state.is(BlockTags.LEAVES))leaves++;
                    }
                    String report="FOUNDATIONS_REGION_PROBE sha256="+HexFormat.of().formatHex(hash.digest())+" blocks="+blocks+" logs="+logs+" leaves="+leaves;
                    c.getSource().sendSuccess(()->Component.literal(report),false);return 1;
                } catch(Exception ex){c.getSource().sendFailure(Component.literal("Probe failed: "+ex.getMessage()));return 0;}
            }))));
    }
}
