package dev.fox.anticheat;

import dev.fox.anticheat.event.DigEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;

// Adapter lifecycle/collection state only
// Check state lives in C++
public final class Session{
    public final long id;
    public final Player player; // access on the server thread only
    public final AtomicInteger pending = new AtomicInteger();
    public final AtomicLong loss = new AtomicLong(); // normalized observation that was dropped before reaching the C++ engine
    public volatile boolean active = true;
    public volatile String failure;
    public long ordinal, reconciledLoss;
    public DigEvent watchedBlock; // Target for periodic context sampling; not a verdict.
    public Session(long id, Player player){ this.id = id; this.player = player; }
}