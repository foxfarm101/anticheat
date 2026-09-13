package dev.fox.anticheat.event;

/** A copied observation, not a reference to a mutable NMS packet. */
public final class DigEvent{
    public enum Action{ START, ABORT, FINISH }
    public final Action action;
    public final int x, y, z, face;
    public final long packetSequence, readBatch, observedNanos, epochMillis;
    public DigEvent(Action action, int x, int y, int z, int face,
                    long packetSequence, long readBatch, long observedNanos, long epochMillis){
        this.action = action; this.x = x; this.y = y; this.z = z; this.face = face;
        this.packetSequence = packetSequence; this.readBatch = readBatch;
        this.observedNanos = observedNanos; this.epochMillis = epochMillis;
    }
}
