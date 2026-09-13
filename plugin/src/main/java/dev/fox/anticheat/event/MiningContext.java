package dev.fox.anticheat.event;

/** Data only. Usability, expected duration, thresholds, and verdicts are C++ decisions. */
public final class MiningContext{
    public final String world, stateKey, block, tool, unavailableReason;
    public final double damagePerTick;
    public final boolean available;
    public MiningContext(String world, String stateKey, String block, String tool,
                         String unavailableReason, double damagePerTick, boolean available){
        this.world = world; this.stateKey = stateKey; this.block = block; this.tool = tool;
        this.unavailableReason = unavailableReason;
        this.damagePerTick = damagePerTick; this.available = available;
    }
}
