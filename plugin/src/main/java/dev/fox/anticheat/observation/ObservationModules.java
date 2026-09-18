/**
 * ObservationModules.java creates the observation collectors used by each session.
 */

package dev.fox.anticheat.observation;

import dev.fox.anticheat.Session;
import dev.fox.anticheat.bridge.ObservationSink;
import java.util.ArrayList;
import java.util.List;

public final class ObservationModules{
    private ObservationModules(){}

    // Add new collection modules here, not in the plugin or packet transport.
    public static List<ObservationModule> create(Session session, ObservationSink sink){
        List<ObservationModule> modules = new ArrayList<>();
        modules.add(new MiningObservations(session, sink));
        return modules;
    }
}
