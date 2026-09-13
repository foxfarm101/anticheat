package dev.fox.anticheat.bridge;

import dev.fox.anticheat.event.DigEvent;
import dev.fox.anticheat.event.MiningContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bridge schema v1, little endian. These are OUR events, not Minecraft wire packets.
 * One owning thread, one reusable buffer; submit synchronously before writing again. */
public final class EventWriter{
    public static final int START = 1, END = 2, RESET = 3, TICK = 4, DIG = 5, CONTEXT = 6;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(8192).order(ByteOrder.LITTLE_ENDIAN);
    public EventWriter begin(int type, long session, long ordinal, long observedNanos,
                             long epochMillis, long serverTick){
        buffer.clear();
        buffer.putInt(0x43415846).putShort((short) 1).putShort((short) type);
        buffer.putLong(session).putLong(ordinal).putLong(observedNanos)
            .putLong(epochMillis).putLong(serverTick);
        return this;
    }
    public EventWriter session(String uuid, int protocol, int model){
        text(uuid); buffer.putInt(protocol).putInt(model); return this;
    }
    public EventWriter text(String value){
        if(value == null || value.length() > 1024){ throw new IllegalArgumentException("Invalid identifier size"); }
        buffer.putShort((short) value.length());
        for(int i = 0; i < value.length(); ++i){
            char c = value.charAt(i);
            if(c < 32 || c > 126){ throw new IllegalArgumentException("Bridge v1 identifiers must be ASCII"); }
            buffer.put((byte) c);
        }
        return this;
    }
    public EventWriter context(int x, int y, int z, MiningContext context){
        buffer.putInt(x).putInt(y).putInt(z);
        snapshot(context); return this;
    }
    public EventWriter dig(DigEvent event, MiningContext context, long sampledNanos){
        buffer.putLong(event.packetSequence).putLong(event.readBatch).putLong(sampledNanos);
        buffer.put((byte) event.action.ordinal()).put((byte) event.face);
        return context(event.x, event.y, event.z, context);
    }
    private void snapshot(MiningContext context){
        text(context.world); text(context.stateKey); text(context.block);
        text(context.tool); text(context.unavailableReason);
        buffer.putDouble(context.damagePerTick).put((byte) (context.available ? 1 : 0));
    }
    public ByteBuffer finish(){ buffer.flip(); return buffer; }
}
