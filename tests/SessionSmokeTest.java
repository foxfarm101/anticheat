/**
 * SessionSmokeTest.java tests Java session numbering and submission through real JNI.
 * No player or running server is needed; the build supplies the Spigot classpath.
 */

package dev.fox.anticheat.bridge;

import dev.fox.anticheat.Session;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SessionSmokeTest{
    private static int passed;

    private static void check(boolean condition, String name){
        if(!condition)
            throw new AssertionError(name);

        ++passed;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) throws Exception{
        NativeBridge.load(new File(args[0]));
        Session session = new Session(1, null);
        List<String> records = new ArrayList<>();
        check(session.ordinal == 1, "new sessions begin at ordinal one");

        try(NativeBridge engine = new NativeBridge("trace=true\nfastbreak.enabled=false")){
            ObservationSink sink = new ObservationSink(
                engine,
                ()->1000000000L,
                ()->20L,
                records::add
            );

            sink.begin(
                EventWriter.START,
                session,
                sink.now(),
                1000
            ).session("java-session", 47, 10808);
            sink.send();
            check(records.size() == 1 && records.get(0).contains("session_open"), "native engine accepts Java session start");
            check(records.get(0).contains("\"event\":\"1\""), "session start carries ordinal one");
            check(session.ordinal == 2, "writer advances the next session ordinal");

            sink.begin(
                EventWriter.RESET,
                session,
                sink.now(),
                1000
            ).text("observation_discontinuity");
            sink.send();
            check(records.get(1).contains("observation_discontinuity"), "shared sink returns reset evidence");
            check(records.get(1).contains("\"event\":\"2\""), "subsequent observations have contiguous ordinals");

            AtomicBoolean rejected = new AtomicBoolean();
            Thread thread = new Thread(()->{
                try{
                    sink.begin(EventWriter.TICK, session, 0, 0);
                }catch(IllegalStateException expected){
                    rejected.set(true);
                }
            });
            thread.start();
            thread.join();
            check(rejected.get() && session.ordinal == 3, "wrong thread cannot modify writer or ordinal");

            sink.begin(EventWriter.END, session, sink.now(), 1000);
            sink.send();
            check(records.get(2).contains("session_closed"), "session end reaches native engine");

            int before = records.size();
            sink.begin(EventWriter.TICK, session, sink.now(), 1000);
            sink.send();
            check(records.size() == before, "late observation does not recreate a session");
        }

        System.out.println(passed + " session/JNI checks passed");
    }
}
