/**
 * MiningObservations.java collects digging requests and their server-state snapshots.
 * It does not calculate thresholds or decide whether mining is suspicious.
 */

package dev.fox.anticheat.observation;

import dev.fox.anticheat.Session;
import dev.fox.anticheat.bridge.EventWriter;
import dev.fox.anticheat.bridge.ObservationSink;
import dev.fox.anticheat.event.DigEvent;
import dev.fox.anticheat.event.MiningContext;
import dev.fox.anticheat.packet.PacketHandlers;
import dev.fox.anticheat.packet.PacketInfo;
import dev.fox.anticheat.version.MiningSampler;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockDig;

public final class MiningObservations implements ObservationModule{
    private final Session session;
    private final ObservationSink sink;
    private final MiningSampler sampler = new MiningSampler();

    // Target for periodic context sampling, not a detector verdict.
    private DigEvent watchedBlock;

    public MiningObservations(Session session, ObservationSink sink){
        this.session = session;
        this.sink = sink;
    }

    @Override
    public void registerHandlers(PacketHandlers handlers){
        handlers.on(
            PacketPlayInBlockDig.class,
            this::copyDig,
            this::onDig
        );
    }

    // Copy packet fields on the network thread without reading Bukkit/world state.
    private DigEvent copyDig(PacketPlayInBlockDig packet, PacketInfo info){
        DigEvent.Action action;

        switch(packet.c()){
            case START_DESTROY_BLOCK:
                action = DigEvent.Action.START;
                break;
            case ABORT_DESTROY_BLOCK:
                action = DigEvent.Action.ABORT;
                break;
            case STOP_DESTROY_BLOCK:
                action = DigEvent.Action.FINISH;
                break;
            default:
                return null;
        }

        BlockPosition position = packet.a();

        return new DigEvent(
            action,
            position.getX(),
            position.getY(),
            position.getZ(),
            packet.b().ordinal(),
            info.sequence,
            info.readBatch,
            info.observedNanos,
            info.epochMillis
        );
    }

    // Sample the digging context on the server thread and send it with the request.
    private void onDig(DigEvent event){
        MiningContext context = sampler.sample(session.player, event);
        long sampledNanos = sink.now();

        sink.begin(
            EventWriter.DIG,
            session,
            event.observedNanos,
            event.epochMillis
        ).dig(
            event,
            context,
            sampledNanos
        );
        sink.send();

        watchedBlock = null;

        if(event.action == DigEvent.Action.START)
            watchedBlock = event;
    }

    // Continue sampling the watched block while the mining attempt is active.
    @Override
    public void onTick(){
        if(watchedBlock == null)
            return;

        MiningContext context = sampler.sample(session.player, watchedBlock);

        sink.begin(
            EventWriter.CONTEXT,
            session,
            sink.now(),
            System.currentTimeMillis()
        ).context(
            watchedBlock.x,
            watchedBlock.y,
            watchedBlock.z,
            context
        );
        sink.send();

        // Send the unavailable snapshot before stopping further samples.
        if(!context.available)
            watchedBlock = null;
    }

    @Override
    public void reset(String reason){
        watchedBlock = null;
    }
}
