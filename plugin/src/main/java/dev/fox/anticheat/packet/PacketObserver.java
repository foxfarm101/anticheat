package dev.fox.anticheat.packet;

import dev.fox.anticheat.Session;
import dev.fox.anticheat.event.DigEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockDig;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.plugin.java.JavaPlugin;

public final class PacketObserver{
    public interface Receiver{ void accept(Session session, DigEvent event, long generation); }
    private static final String NAME = "fox_anticheat_native";
    private final JavaPlugin plugin;
    private final Receiver receiver;
    private final LongSupplier clock;
    private final MinecraftServer server = MinecraftServer.getServer();
    private final Map<Long, Channel> channels = new HashMap<>(); // Server thread only.
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean running = true;
    public PacketObserver(JavaPlugin plugin, LongSupplier clock, Receiver receiver){
        this.plugin = plugin; this.clock = clock; this.receiver = receiver;
    }
    public void attach(Session session){
        Channel channel = ((CraftPlayer) session.player).getHandle().playerConnection.networkManager.channel;
        channels.put(session.id, channel);
        channel.eventLoop().execute(() -> {
            if(!running || !session.active || !channel.isOpen()){ return; }
            try{
                if(channel.pipeline().get("packet_handler") == null || channel.pipeline().get(NAME) != null){
                    throw new IllegalStateException("Unexpected pipeline; restart server without translators");
                }
                channel.pipeline().addBefore("packet_handler", NAME, new ChannelInboundHandlerAdapter(){
                    private long sequence, batch;
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception{
                        try{
                            long currentSequence = ++sequence;
                            if(running && session.active){
                                DigEvent event = normalize(message, currentSequence, batch);
                                if(event != null){ submit(session, event); }
                            }
                        }catch(RuntimeException | LinkageError error){ fail(session, error); }
                        finally{ ctx.fireChannelRead(message); }
                    }
                    @Override
                    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception{
                        ++batch; ctx.fireChannelReadComplete();
                    }
                });
                plugin.getLogger().info("Packet observer attached: session=" + session.id);
            }catch(RuntimeException | LinkageError error){ fail(session, error); }
        });
    }
    private DigEvent normalize(Object message, long sequence, long batch){
        if(!(message instanceof PacketPlayInBlockDig)){ return null; }
        PacketPlayInBlockDig packet = (PacketPlayInBlockDig) message;
        DigEvent.Action action;
        switch(packet.c()){
            case START_DESTROY_BLOCK: action = DigEvent.Action.START; break;
            case ABORT_DESTROY_BLOCK: action = DigEvent.Action.ABORT; break;
            case STOP_DESTROY_BLOCK: action = DigEvent.Action.FINISH; break;
            default: return null;
        }
        BlockPosition p = packet.a();
        return new DigEvent(action, p.getX(), p.getY(), p.getZ(), packet.b().ordinal(),
            sequence, batch, clock.getAsLong(), System.currentTimeMillis());
    }
    private void submit(Session session, DigEvent event){
        int perPlayer = session.pending.incrementAndGet();
        int total = pending.incrementAndGet();
        if(perPlayer > 128 || total > 1024){
            session.pending.decrementAndGet(); pending.decrementAndGet();
            session.loss.incrementAndGet(); return;
        }
        long generation = session.loss.get();
        try{
            // Queue on the same FIFO used by 1.8.8 gameplay packet processing,
            // BEFORE forwarding that packet. This is intentionally version-specific.
            server.postToMainThread(() -> {
                try{
                    if(running && session.active){ receiver.accept(session, event, generation); }
                }catch(RuntimeException | LinkageError error){ fail(session, error); }
                finally{ session.pending.decrementAndGet(); pending.decrementAndGet(); }
            });
        }catch(RuntimeException | LinkageError error){
            session.pending.decrementAndGet(); pending.decrementAndGet(); throw error;
        }
    }
    private void fail(Session session, Throwable error){
        session.failure = error.toString(); session.active = false;
        plugin.getLogger().severe("Observer disabled for session=" + session.id + ": " + error);
    }
    public void detach(long id){
        Channel channel = channels.remove(id);
        if(channel == null){ return; }
        try{
            channel.eventLoop().execute(() -> {
                if(channel.pipeline().get(NAME) != null){ channel.pipeline().remove(NAME); }
            });
        }catch(RejectedExecutionException ignored){ /* Channel event loop is already stopped. */ }
    }
    public void close(){
        running = false;
        for(Long id : channels.keySet().toArray(new Long[0])){ detach(id); }
    }
}
