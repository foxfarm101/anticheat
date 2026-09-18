/**
 * ObservationModule.java defines a per-session source of normalized observations.
 * Modules collect data; detection rules remain in C++ checks.
 */

package dev.fox.anticheat.observation;

import dev.fox.anticheat.packet.PacketHandlers;

public interface ObservationModule{
    // Register the packet types this module needs to observe.
    void registerHandlers(PacketHandlers handlers);

    // Collect periodic server state when the module needs it.
    default void onTick(){}

    // Clear collection history after lost observations or a session boundary.
    default void reset(String reason){}
}
