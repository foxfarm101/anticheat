/**
 * AntiCheatPlugin.java initializes the Java adapter when Spigot enables the plugin.
 * It manages sessions and connects observation modules to the C++ engine.
 */

package dev.fox.anticheat;

import dev.fox.anticheat.bridge.EventWriter;
import dev.fox.anticheat.bridge.NativeBridge;
import dev.fox.anticheat.bridge.ObservationSink;
import dev.fox.anticheat.observation.ObservationModule;
import dev.fox.anticheat.observation.ObservationModules;
import dev.fox.anticheat.packet.PacketObserver;
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
    private static final int CLIENT_PROTOCOL = 47;
    private static final int SERVER_MODEL = 10808;

    // Active Java-side sessions; each has its own collection modules.
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final long originNanos = System.nanoTime();
    private long nextSession = 1;

    private NativeBridge engine;
    private ObservationSink observations;
    private PacketObserver packets;
    private BukkitTask ticker;
    private boolean running;

    private long now(){
        return System.nanoTime() - originNanos;
    }

    private long tick(){
        return Integer.toUnsignedLong(MinecraftServer.currentTick);
    }

    // The current collectors assume direct 1.8.x clients, not translated protocols.
    private void checkServer(){
        if(!Bukkit.getServer().getClass().getPackage().getName().endsWith("v1_8_R3"))
            throw new IllegalStateException("This adapter is pinned to Spigot v1_8_R3");

        for(String name : new String[]{"ViaVersion", "ViaBackwards", "ProtocolSupport"}){
            if(getServer().getPluginManager().getPlugin(name) != null)
                throw new IllegalStateException("Remove protocol translators from this initial lab");
        }
    }

    // Load runtime configuration and the native library from the plugin data directory.
    private void openEngine() throws Exception{
        if(!getDataFolder().isDirectory() && !getDataFolder().mkdirs())
            throw new IllegalStateException("Cannot create plugin data directory");

        File config = new File(getDataFolder(), "engine.conf");

        if(!config.isFile())
            saveResource("engine.conf", false);

        File library = new File(getDataFolder(), System.mapLibraryName("anticheat_native"));

        if(!library.isFile())
            throw new IllegalStateException("Missing native library: " + library.getAbsolutePath());

        NativeBridge.load(library);
        String configuration = new String(
            Files.readAllBytes(config.toPath()),
            StandardCharsets.UTF_8
        );
        engine = new NativeBridge(configuration);

        observations = new ObservationSink(
            engine,
            this::now,
            this::tick,
            record->getLogger().info(record)
        );
    }

    @Override
    public void onEnable(){
        try{
            checkServer();
            openEngine();
            packets = new PacketObserver(
                this,
                this::now,
                this::receive
            );
            running = true;

            getServer().getPluginManager().registerEvents(this, this);
            ticker = getServer().getScheduler().runTaskTimer(
                this,
                this::onTick,
                1L,
                1L
            );

            for(Player player : getServer().getOnlinePlayers()){
                join(player);
            }

            getLogger().info("C++ detection engine loaded. Java adapter: Spigot 1.8.8. Report-only; no enforcement.");
        }catch(Exception | LinkageError error){
            getLogger().log(
                Level.SEVERE,
                "Could not enable native anticheat",
                error
            );
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    // Clear collection history and tell the C++ checks that observations are discontinuous.
    private void reset(Session session, String reason){
        for(ObservationModule module : session.modules){
            module.reset(reason);
        }

        observations.begin(
            EventWriter.RESET,
            session,
            now(),
            System.currentTimeMillis()
        ).text(reason);
        observations.send();
    }

    // Acknowledge new loss before accepting further observations for this session.
    private void reconcileLoss(Session session){
        long loss = session.loss.get();

        if(loss != session.reconciledLoss){
            session.reconciledLoss = loss;
            reset(session, "observation_discontinuity");
        }
    }

    // Run callbacks for copied packet observations on the server thread.
    private void receive(Session session, Runnable observation, long generation){
        if(!running || !session.active)
            return;

        reconcileLoss(session);

        if(generation != session.loss.get())
            return;

        observation.run();
    }

    // Ask every module for periodic observations, then send the session's TickEvent.
    private void onTick(){
        if(!running)
            return;

        Iterator<Session> iterator = sessions.values().iterator();

        while(iterator.hasNext()){
            Session session = iterator.next();

            if(!session.active){
                end(session);
                iterator.remove();
                continue;
            }

            try{
                reconcileLoss(session);

                for(ObservationModule module : session.modules){
                    module.onTick();
                }

                observations.begin(
                    EventWriter.TICK,
                    session,
                    now(),
                    System.currentTimeMillis()
                );
                observations.send();
            }catch(RuntimeException | LinkageError error){
                fail(session, "Native processing disabled", error);
            }
        }
    }

    // Create a session and its collectors when a player joins or is already online at enable.
    private void join(Player player){
        if(!running || sessions.containsKey(player.getUniqueId()))
            return;

        Session session = new Session(nextSession++, player);
        sessions.put(player.getUniqueId(), session);

        try{
            session.modules.addAll(ObservationModules.create(session, observations));

            for(ObservationModule module : session.modules){
                module.registerHandlers(session.handlers);
            }

            observations.begin(
                EventWriter.START,
                session,
                now(),
                System.currentTimeMillis()
            ).session(
                player.getUniqueId().toString(),
                CLIENT_PROTOCOL,
                SERVER_MODEL
            );
            observations.send();
            packets.attach(session);
        }catch(RuntimeException | LinkageError error){
            fail(session, "Cannot attach", error);
        }
    }

    // End this session's collection and release its native detection state.
    private void end(Session session){
        session.active = false;

        if(packets != null)
            packets.detach(session.id);

        for(ObservationModule module : session.modules){
            try{
                module.reset("session_end");
            }catch(RuntimeException | LinkageError error){
                fail(session, "Collection cleanup failed", error);
            }
        }

        if(observations == null)
            return;

        try{
            observations.begin(
                EventWriter.END,
                session,
                now(),
                System.currentTimeMillis()
            );
            observations.send();
        }catch(RuntimeException | LinkageError error){
            fail(session, "Session cleanup failed", error);
        }
    }

    private void fail(Session session, String message, Throwable error){
        session.active = false;
        session.failure = error.toString();
        getLogger().log(
            Level.SEVERE,
            message + " for session=" + session.id,
            error
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event){
        join(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event){
        Session session = sessions.remove(event.getPlayer().getUniqueId());

        if(session != null)
            end(session);
    }

    // Preserve the current conservative reset until teleports have their own typed event.
    // TODO: Represent authorized teleports explicitly; preserve unrelated detector evidence.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event){
        Session session = sessions.get(event.getPlayer().getUniqueId());

        if(session == null || !session.active)
            return;

        session.loss.incrementAndGet();

        try{
            reconcileLoss(session);
        }catch(RuntimeException | LinkageError error){
            fail(session, "Teleport reset failed", error);
        }
    }

    // Stop observation before destroying sessions and the native engine.
    @Override
    public void onDisable(){
        running = false;

        if(ticker != null){
            ticker.cancel();
            ticker = null;
        }

        if(packets != null)
            packets.close();

        for(Session session : sessions.values()){
            end(session);
        }

        sessions.clear();

        if(engine != null){
            try{
                engine.close();
            }catch(RuntimeException | LinkageError error){
                getLogger().log(
                    Level.SEVERE,
                    "Native cleanup failed",
                    error
                );
            }

            engine = null;
        }

        observations = null;
    }
}
