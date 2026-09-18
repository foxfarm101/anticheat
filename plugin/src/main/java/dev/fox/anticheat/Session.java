/**
 * Session.java stores Java-side connection and observation state for one player.
 * Detector state belongs to the C++ checks, not this class.
 */

package dev.fox.anticheat;

import dev.fox.anticheat.observation.ObservationModule;
import dev.fox.anticheat.packet.PacketHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;

public final class Session{
    public final long id;
    public final Player player; // Access live player state only on the server thread.
    public final PacketHandlers handlers = new PacketHandlers();
    public final List<ObservationModule> modules = new ArrayList<>();

    // Network/server handoff bookkeeping; these fields cross thread boundaries.
    public final AtomicInteger pending = new AtomicInteger();
    public final AtomicLong loss = new AtomicLong();
    public volatile boolean active = true;
    public volatile String failure;

    // The native engine requires nonzero event ordinals.
    public long ordinal = 1;
    public long reconciledLoss;

    public Session(long id, Player player){
        this.id = id;
        this.player = player;
    }
}
