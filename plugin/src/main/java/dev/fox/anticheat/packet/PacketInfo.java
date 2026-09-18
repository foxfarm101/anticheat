/**
 * PacketInfo.java contains the ordering and timing metadata copied for one packet.
 */

package dev.fox.anticheat.packet;

public final class PacketInfo{
    public final long sequence;
    public final long readBatch;
    public final long observedNanos;
    public final long epochMillis;

    public PacketInfo(long sequence, long readBatch, long observedNanos, long epochMillis){
        this.sequence = sequence;
        this.readBatch = readBatch;
        this.observedNanos = observedNanos;
        this.epochMillis = epochMillis;
    }
}
