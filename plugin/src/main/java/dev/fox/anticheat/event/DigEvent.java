/**
 * DigEvent.java stores a copied digging request and its packet observation metadata.
 */

package dev.fox.anticheat.event;

public final class DigEvent{
    public enum Action{
        START,
        ABORT,
        FINISH
    }

    public final Action action;
    public final int x;
    public final int y;
    public final int z;
    public final int face;
    public final long packetSequence;
    public final long readBatch;
    public final long observedNanos;
    public final long epochMillis;

    public DigEvent(
        Action action,
        int x,
        int y,
        int z,
        int face,
        long packetSequence,
        long readBatch,
        long observedNanos,
        long epochMillis
    ){
        this.action = action;
        this.x = x;
        this.y = y;
        this.z = z;
        this.face = face;
        this.packetSequence = packetSequence;
        this.readBatch = readBatch;
        this.observedNanos = observedNanos;
        this.epochMillis = epochMillis;
    }
}
