/**
 * MiningContext.java contains a snapshot of server-side mining conditions.
 * C++ checks decide whether the snapshot is usable for detection.
 */

package dev.fox.anticheat.event;

public final class MiningContext{
    public final String world;
    public final String stateKey;
    public final String block;
    public final String tool;
    public final String unavailableReason;
    public final double damagePerTick;
    public final boolean available;

    public MiningContext(
        String world,
        String stateKey,
        String block,
        String tool,
        String unavailableReason,
        double damagePerTick,
        boolean available
    ){
        this.world = world;
        this.stateKey = stateKey;
        this.block = block;
        this.tool = tool;
        this.unavailableReason = unavailableReason;
        this.damagePerTick = damagePerTick;
        this.available = available;
    }
}
