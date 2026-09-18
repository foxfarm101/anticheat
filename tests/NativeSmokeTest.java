/**
 * NativeSmokeTest.java tests the real Java/C++ JNI boundary without Minecraft.
 */

package dev.fox.anticheat.bridge;

import dev.fox.anticheat.event.DigEvent;
import dev.fox.anticheat.event.MiningContext;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeSmokeTest{
    private static int passed;

    private static void check(boolean condition, String name){
        if(!condition)
            throw new AssertionError(name);

        ++passed;
        System.out.println("PASS " + name);
    }

    private static boolean contains(String[] records, String text){
        if(records == null)
            return false;

        for(String record : records){
            if(record.contains(text))
                return true;
        }

        return false;
    }

    // Keep expected exception checks separate from the test's normal control flow.
    private static void rejects(Runnable operation, String name){
        boolean rejected = false;

        try{
            operation.run();
        }catch(IllegalStateException expected){
            rejected = true;
        }

        check(rejected, name);
    }

    public static void main(String[] args) throws Exception{
        NativeBridge.load(new File(args[0]));
        EventWriter writer = new EventWriter();
        MiningContext context = new MiningContext(
            "world",
            "stone|hand",
            "STONE",
            "AIR",
            "",
            0.02,
            true
        );

        try(NativeBridge engine = new NativeBridge("trace=true\nfastbreak.alert_after=1")){
            writer.begin(
                EventWriter.START,
                42,
                1,
                0,
                10,
                1
            ).session(
                "11111111-1111-1111-1111-111111111111",
                47,
                10808
            );
            String[] result = engine.submit(writer.finish());
            check(contains(result, "session_open"), "Java to C++ session creation");

            DigEvent start = new DigEvent(
                DigEvent.Action.START,
                -10,
                64,
                15,
                1,
                10,
                1,
                1000000000L,
                1000
            );
            writer.begin(
                EventWriter.DIG,
                42,
                2,
                start.observedNanos,
                1000,
                2
            ).dig(start, context, start.observedNanos);
            result = engine.submit(writer.finish());
            check(contains(result, "expected_ms"), "normalized dig and context decoded");

            DigEvent finish = new DigEvent(
                DigEvent.Action.FINISH,
                -10,
                64,
                15,
                1,
                11,
                2,
                1100000000L,
                1100
            );
            writer.begin(
                EventWriter.DIG,
                42,
                3,
                finish.observedNanos,
                1100,
                4
            ).dig(finish, context, finish.observedNanos);
            result = engine.submit(writer.finish());
            check(contains(result, "\"level\":\"suspicious\""), "C++ finding returned to Java");
            check(
                contains(result, "-10,64,15") && contains(result, "NOT_MEASURED"),
                "signed coordinates and outcome evidence"
            );

            if(result != null){
                for(String record : result){
                    System.out.println("JSON " + record);
                }
            }

            writer.begin(
                EventWriter.DIG,
                42,
                4,
                1200000000L,
                1200,
                5
            ).dig(finish, context, 1200000000L);
            result = engine.submit(writer.finish());
            check(!contains(result, "\"level\":\"suspicious\""), "duplicate finish not re-alerted");

            // Corrupt the schema version without changing the rest of the header.
            ByteBuffer bad = writer.begin(
                EventWriter.TICK,
                42,
                5,
                1300000000L,
                1300,
                6
            ).finish();
            bad.put(4, (byte) 9);
            rejects(()->engine.submit(bad), "schema mismatch becomes Java exception");
            rejects(()->engine.submit(ByteBuffer.allocateDirect(1)), "truncated buffer rejected safely");

            writer.begin(
                EventWriter.RESET,
                42,
                5,
                1300000000L,
                1300,
                6
            ).text("teleport");
            result = engine.submit(writer.finish());
            check(contains(result, "teleport"), "generic reset traverses bridge");

            AtomicBoolean wrongThread = new AtomicBoolean();
            Thread thread = new Thread(()->{
                try{
                    engine.submit(ByteBuffer.allocateDirect(1));
                }catch(IllegalStateException expected){
                    wrongThread.set(true);
                }
            });
            thread.start();
            thread.join();
            check(wrongThread.get(), "thread ownership enforced");

            engine.submit(writer.begin(
                EventWriter.END,
                42,
                6,
                1400000000L,
                1400,
                7
            ).finish());
            result = engine.submit(writer.begin(
                EventWriter.TICK,
                42,
                7,
                1500000000L,
                1500,
                8
            ).finish());
            check(result == null, "closed session ignores late data");

            engine.close();
            engine.close();
            rejects(
                ()->engine.submit(ByteBuffer.allocateDirect(1)),
                "closed engine rejects calls; close is idempotent"
            );
        }

        rejects(()->new NativeBridge("unknown=true"), "native configuration validation reaches Java");

        try(NativeBridge disabled = new NativeBridge("fastbreak.enabled=false")){
            writer.begin(
                EventWriter.START,
                1,
                1,
                0,
                0,
                0
            ).session("player", 47, 10808);
            String[] result = disabled.submit(writer.finish());
            check(contains(result, "session_open"), "disabling FastBreak does not disable engine");
        }

        System.out.println(passed + " JNI smoke checks passed");
    }
}
