/**
 * PacketHandlers.java connects packet types to their observation collectors.
 * Packet data is copied on the network thread; receivers run on the server thread.
 */

package dev.fox.anticheat.packet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public final class PacketHandlers{
    private interface Handler{
        Runnable capture(Object packet, PacketInfo info);
    }

    private final Map<Class<?>, List<Handler>> handlers = new HashMap<>();
    private boolean sealed;

    // Register a packet copier and the receiver for its normalized observation.
    // The copier must return owned data, never a live NMS packet or world object.
    public <P, E> void on(
        Class<P> type,
        BiFunction<P, PacketInfo, E> copy,
        Consumer<E> receive
    ){
        if(sealed)
            throw new IllegalStateException("Register handlers before attaching observation");

        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(copy, "copy");
        Objects.requireNonNull(receive, "receive");

        Handler handler = (packet, info)->{
            E event = copy.apply(type.cast(packet), info);

            if(event == null)
                return null;

            return ()->receive.accept(event);
        };

        List<Handler> registered = handlers.computeIfAbsent(type, ignored->new ArrayList<>());
        registered.add(handler);
    }

    // Freeze registration before this registry is handed to the network thread.
    public void seal(){
        sealed = true;
    }

    // Copy registered observations now and return their server-thread callbacks.
    // Matching is by exact packet class; register subclasses explicitly when needed.
    public Runnable capture(Object packet, PacketInfo info){
        if(!sealed)
            throw new IllegalStateException("Packet handlers are not sealed");

        List<Handler> registered = handlers.get(packet.getClass());

        if(registered == null)
            return null;

        List<Runnable> callbacks = new ArrayList<>();

        for(Handler handler : registered){
            Runnable callback = handler.capture(packet, info);

            if(callback != null)
                callbacks.add(callback);
        }

        if(callbacks.isEmpty())
            return null;

        return ()->{
            for(Runnable callback : callbacks){
                callback.run();
            }
        };
    }
}
