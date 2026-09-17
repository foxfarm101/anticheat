/**
 * AntiCheatPlugin.java initializes the Java side of the anticheat when Spigot loads it.
 * Spigot sees the anticheat.jar plugin in its /plugins dir, loads it, and enables it by calling AntiCheatPlugin.onEnable().
 */

package dev.fox.anticheat;

import dev.fox.anticheat.bridge.EventWriter;
import dev.fox.anticheat.bridge.NativeBridge;
import dev.fox.anticheat.event.DigEvent;
import dev.fox.anticheat.event.MiningContext;
import dev.fox.anticheat.packet.PacketObserver;
import dev.fox.anticheat.version.MiningSampler;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class AntiCheatPlugin extends JavaPlugin implements Listener{
    // active Java-side player sessions indexed by player UUID
    private final Map<UUID, Session> sessions = new HashMap<>();

    // Serializes the normalized observations (Java) into the byte format expected by C++
    private final EventWriter writer = new EventWriter();

    // Snapshots relevant server state for mining observations
    private final MiningSampler sampler = new MiningSampler();

    // Monotonic time origin used for observation timestamps
    private final long originNanos = System.nanoTime();
    
    private long nextSession = 1;
    private NativeBridge engine;
    private PacketObserver packets;
    private BukkitTask ticker;
    private boolean running;

    private long now(){
        return System.nanoTime() - originNanos;
    }

    private long tick(){
        return Integer.toUnsignedLong(MinecraftServer.currentTick);
    }

    @Override
    public void onEnable(){ // Spigot has started/enabled the plugin
        try{
            if(!Bukkit.getServer().getClass().getPackage().getName().endsWith("v1_8_R3")){
                throw new IllegalStateException("This adapter is pinned to Spigot v1_8_R3");
            }

            // The Java adapter currently only expects the player to connect using 1.8.x (protocol 47) directly to the Spigot 1.8.8 server.
            // It does not account for protocol translators such as ViaVersion.
            for(String name : new String[]{"ViaVersion", "ViaBackwards", "ProtocolSupport"}){
                if(getServer().getPluginManager().getPlugin(name) != null){
                    throw new IllegalStateException("Remove protocol translators from this initial lab");
                }
            }

            // If the plugin's data directory doesn't exist, create it. If creation fails, stop initialization.
            if(!getDataFolder().isDirectory() && !getDataFolder().mkdirs()){
                throw new IllegalStateException("Cannot create plugin data directory");
            }

            // engine.conf = config file for the C++ anticheat code
            File config = new File(getDataFolder(), "engine.conf");
            if(!config.isFile()){
                saveResource("engine.conf", false);
            }

            // anticheat_native.dll = compiled C++ anticheat code
            File library = new File(getDataFolder(), System.mapLibraryName("anticheat_native"));
            if(!library.isFile()){
                throw new IllegalStateException("Missing native library: " + library.getAbsolutePath());
            }

            // load the compiled C++ anticheat library into the Spigot JVM process
            NativeBridge.load(library);
            String configuration = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
            engine = new NativeBridge(configuration);
            packets = new PacketObserver(this, this::now, this::receive);
            running = true;
            
            getServer().getPluginManager().registerEvents(this, this);
            ticker = getServer().getScheduler().runTaskTimer(this, this::onTick, 1L, 1L);
            for(Player player : getServer().getOnlinePlayers()){
                join(player);
            }
            getLogger().info("C++ detection engine loaded. Java adapter: Spigot 1.8.8. Report-only; no enforcement.");
        }catch(Exception | LinkageError error){
            getLogger().log(Level.SEVERE, "Could not enable native anticheat", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    // start serializing a new normalized observation into a byte buffer
    private EventWriter begin(int kind, Session session, long time, long epoch){
        return writer.begin(kind, session.id, session.ordinal++, time, epoch, tick());
    }

    // send observation through JNI to C++
    private void send(){
        String[] records = engine.submit(writer.finish());
        if(records != null){
            for(String record : records){
                getLogger().info(record);
            }
        }
    }

    // Clear Java-side mining tracking and tell the C++ checks to reset their detection state.
    private void reset(Session session, String reason){
        session.watchedBlock = null;
        begin(EventWriter.RESET, session, now(), System.currentTimeMillis()).text(reason);
        send();
    }

    // update the session's record of how much loss has already been handled and send a reset to the engine
    private void reconcileLoss(Session session){
        long loss = session.loss.get();
        if(loss != session.reconciledLoss){
            session.reconciledLoss = loss;
            reset(session, "observation_discontinuity");
        }
    }

    // receive normalized DigEvent from the Java adapter and process it
    private void receive(Session session, DigEvent event, long generation){
        if(!running || !session.active) return;
        reconcileLoss(session);
        if(generation != session.loss.get()) return;

        MiningContext context = sampler.sample(session.player, event);
        long sampledNanos = now();
        begin(EventWriter.DIG, session, event.observedNanos, event.epochMillis)
            .dig(event, context, sampledNanos);
        send();

        // bookkeeping for future context samples
        session.watchedBlock = event.action == DigEvent.Action.START ? event : null;
    }

    // periodically update active sessions, sample watched mining state, and send TickEvents.
    private void onTick(){
        if(!running) return;
        Iterator<Session> iterator = sessions.values().iterator();
        while(iterator.hasNext()){
            Session session = iterator.next();
            if(!session.active){
                end(session); iterator.remove(); continue;
            }
            try{
                reconcileLoss(session);
                DigEvent target = session.watchedBlock;
                if(target != null){
                    MiningContext context = sampler.sample(session.player, target);
                    
                    begin(EventWriter.CONTEXT, session, now(), System.currentTimeMillis())
                        .context(target.x, target.y, target.z, context);
                        send();

                    // Stop polling an obsolete target; C++ receives the unavailable snapshot first.
                    if(!context.available){
                        session.watchedBlock = null;
                    }
                }
                begin(EventWriter.TICK, session, now(), System.currentTimeMillis());
                send();
            }catch(RuntimeException | LinkageError error){
                session.active = false;
                getLogger().log(Level.SEVERE, "Native processing disabled for session=" + session.id, error);
            }
        }
    }

    // Create a new anticheat session and attach packet observation when a player joins
    private void join(Player player){
        if(!running || sessions.containsKey(player.getUniqueId()))
            return;

        Session session = new Session(nextSession++, player);
        sessions.put(player.getUniqueId(), session);

        try{
            begin(EventWriter.START, session, now(), System.currentTimeMillis())
                .session(player.getUniqueId().toString(), 47, 10808);
            send();
            packets.attach(session);
        }catch(RuntimeException | LinkageError error){
            session.active = false;
            getLogger().log(Level.SEVERE, "Cannot attach session=" + session.id, error);
        }
    }

    // End a player session, detach packet observation, and notify the C++ engine.
    private void end(Session session){
        session.active = false;
        session.watchedBlock = null;
        if(packets != null){
            packets.detach(session.id);
        }
        if(engine != null){
            try{ begin(EventWriter.END, session, now(), System.currentTimeMillis()); send(); }
            catch(RuntimeException | LinkageError error){ getLogger().log(Level.SEVERE, "Session cleanup failed", error); }
        }
    }

    // Start an anticheat session when a player joins the server.
    @EventHandler
    public void onJoin(PlayerJoinEvent event){
        join(event.getPlayer());
    }

    // End and remove the player's anticheat session when they leave the server
    @EventHandler
    public void onQuit(PlayerQuitEvent event){
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if(session != null)
            end(session);
    }

    // Invalidate queued observations and reset check state after a server-recognized teleport
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event){
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if(session != null && session.active){
            // Also invalidates already queued observations from before the teleport.
            session.loss.incrementAndGet();
            try{
                reconcileLoss(session);
            }catch(RuntimeException | LinkageError error){
                session.active = false;
            }
        }
    }

    // Stop observation, end all active sessions, and shut down the native C++ engine.
    @Override
    public void onDisable(){
        running = false;
        if(ticker != null){
            ticker.cancel(); ticker = null;
        }
        if(packets != null){
            packets.close();
        }
        for(Session session : sessions.values()){
            end(session);
        }
        sessions.clear();
        if(engine != null){
            try{
                engine.close();
            }
            catch(RuntimeException | LinkageError error){
                getLogger().log(Level.SEVERE, "Native cleanup failed", error);
            }
            engine = null;
        }
    }
}
