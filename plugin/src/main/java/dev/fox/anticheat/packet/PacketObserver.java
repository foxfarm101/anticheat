/**
 * PacketObserver.java observes decoded packets and queues copied observations.
 * Packet-specific conversion belongs to registered collection modules.
 */

package dev.fox.anticheat.packet;

import dev.fox.anticheat.Session;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.plugin.java.JavaPlugin;

public final class PacketObserver{
    public interface Receiver{
        void accept(Session session, Runnable observation, long generation);
    }

    private static final String NAME = "fox_anticheat_native";
    private static final int PLAYER_QUEUE_LIMIT = 128;
    private static final int TOTAL_QUEUE_LIMIT = 1024;

    private final JavaPlugin plugin;
    private final Receiver receiver;
    private final LongSupplier clock;
    private final MinecraftServer server = MinecraftServer.getServer();
    private final Map<Long, Channel> channels = new HashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean running = true;

    public PacketObserver(JavaPlugin plugin, LongSupplier clock, Receiver receiver){
        this.plugin = plugin;
        this.clock = clock;
        this.receiver = receiver;
    }

    // Install this session's observer before normal NMS packet processing.
    // Call on the server thread; pipeline changes run on the channel's event loop.
    public void attach(Session session){
        session.handlers.seal();

        Channel channel = ((CraftPlayer) session.player).getHandle()
            .playerConnection.networkManager.channel;
        channels.put(session.id, channel);

        channel.eventLoop().execute(()->{
            if(!running || !session.active || !channel.isOpen())
                return;

            try{
                if(channel.pipeline().get("packet_handler") == null || channel.pipeline().get(NAME) != null)
                    throw new IllegalStateException("Unexpected pipeline; restart server without translators");

                channel.pipeline().addBefore(
                    "packet_handler",
                    NAME,
                    new ConnectionObserver(session)
                );

                plugin.getLogger().info("Packet observer attached: session=" + session.id);
            }catch(RuntimeException | LinkageError error){
                fail(session, error);
            }
        });
    }

    // Each connection has its own packet order and Netty read-cycle counter.
    private final class ConnectionObserver extends ChannelInboundHandlerAdapter{
        private final Session session;
        private long sequence;
        private long batch;

        private ConnectionObserver(Session session){
            this.session = session;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception{
            try{
                long currentSequence = ++sequence;

                if(!running || !session.active)
                    return;

                // Capture the generation before copying, so a concurrent reset invalidates this work.
                long generation = session.loss.get();
                PacketInfo info = new PacketInfo(
                    currentSequence,
                    batch,
                    clock.getAsLong(),
                    System.currentTimeMillis()
                );
                Runnable observation = session.handlers.capture(message, info);

                if(observation != null)
                    submit(session, observation, generation);
            }catch(RuntimeException | LinkageError error){
                fail(session, error);
            }finally{
                // Observation must never swallow or forward the gameplay packet twice.
                ctx.fireChannelRead(message);
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception{
            // A read cycle is not a client tick or necessarily one socket read.
            ++batch;
            ctx.fireChannelReadComplete();
        }
    }

    // Bound queued work and hand the copied observations to the server thread.
    private void submit(Session session, Runnable observation, long generation){
        int perPlayer = session.pending.incrementAndGet();
        int total = pending.incrementAndGet();

        if(perPlayer > PLAYER_QUEUE_LIMIT || total > TOTAL_QUEUE_LIMIT){
            release(session);
            session.loss.incrementAndGet();
            return;
        }

        try{
            // Spigot 1.8.8 uses this queue for gameplay packet processing.
            // Queue our snapshot first, then let channelRead forward the original packet.
            server.postToMainThread(()->{
                try{
                    if(running && session.active)
                        receiver.accept(session, observation, generation);
                }catch(RuntimeException | LinkageError error){
                    fail(session, error);
                }finally{
                    release(session);
                }
            });
        }catch(RuntimeException | LinkageError error){
            release(session);
            throw error;
        }
    }

    private void release(Session session){
        session.pending.decrementAndGet();
        pending.decrementAndGet();
    }

    // Disable only this session's collection; normal gameplay packets still continue.
    private void fail(Session session, Throwable error){
        session.failure = error.toString();
        session.active = false;
        plugin.getLogger().severe("Observer disabled for session=" + session.id + ": " + error);
    }

    public void detach(long id){
        Channel channel = channels.remove(id);

        if(channel == null)
            return;

        try{
            channel.eventLoop().execute(()->{
                if(channel.pipeline().get(NAME) != null)
                    channel.pipeline().remove(NAME);
            });
        }catch(RejectedExecutionException ignored){
            // A stopped event loop no longer delivers packets to this observer.
        }
    }

    // Stop accepting observations and remove every connection handler.
    public void close(){
        running = false;

        for(Long id : channels.keySet().toArray(new Long[0])){
            detach(id);
        }
    }
}
