/**
 * ObservationSink.java sends normalized observations to C++ and reports returned Findings.
 */

package dev.fox.anticheat.bridge;

import dev.fox.anticheat.Session;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class ObservationSink{
    private final Thread owner = Thread.currentThread();
    private final EventWriter writer = new EventWriter();
    private final NativeBridge engine;
    private final LongSupplier clock;
    private final LongSupplier tick;
    private final Consumer<String> report;

    public ObservationSink(
        NativeBridge engine,
        LongSupplier clock,
        LongSupplier tick,
        Consumer<String> report
    ){
        this.engine = engine;
        this.clock = clock;
        this.tick = tick;
        this.report = report;
    }

    public long now(){
        return clock.getAsLong();
    }

    private void checkThread(){
        if(Thread.currentThread() != owner)
            throw new IllegalStateException("Observation writing requires the server thread");
    }

    // Write the common EventHeader before the caller writes its typed payload.
    public EventWriter begin(int kind, Session session, long time, long epoch){
        checkThread();

        return writer.begin(
            kind,
            session.id,
            session.ordinal++,
            time,
            epoch,
            tick.getAsLong()
        );
    }

    // Submit before reusing the writer; native processing completes synchronously.
    public void send(){
        checkThread();
        String[] records = engine.submit(writer.finish());

        if(records == null)
            return;

        for(String record : records){
            report.accept(record);
        }
    }
}
