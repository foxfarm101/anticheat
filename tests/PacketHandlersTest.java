/**
 * PacketHandlersTest.java tests observation routing without Minecraft or JNI.
 */

package dev.fox.anticheat.packet;

import dev.fox.anticheat.observation.ObservationModule;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class PacketHandlersTest{
    private static int passed;

    private static class DigPacket{
        int value;

        DigPacket(int value){
            this.value = value;
        }
    }

    private static final class AttackPacket{}
    private static final class OtherDigPacket extends DigPacket{
        OtherDigPacket(){
            super(1);
        }
    }

    // Test collector with its own state and two independent packet interests.
    private static final class Probe implements ObservationModule{
        int digs;
        int attacks;

        @Override
        public void registerHandlers(PacketHandlers handlers){
            handlers.on(
                DigPacket.class,
                (packet, info)->packet.value,
                value->digs += value
            );
            handlers.on(
                AttackPacket.class,
                (packet, info)->info.sequence,
                sequence->++attacks
            );
        }

        @Override
        public void reset(String reason){
            digs = 0;
            attacks = 0;
        }
    }

    private static void check(boolean condition, String name){
        if(!condition)
            throw new AssertionError(name);

        ++passed;
        System.out.println("PASS " + name);
    }

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
        PacketInfo info = new PacketInfo(10, 4, 123000000L, 456L);
        PacketHandlers handlers = new PacketHandlers();
        Probe first = new Probe();
        Probe second = new Probe();
        first.registerHandlers(handlers);
        second.registerHandlers(handlers);

        rejects(
            ()->handlers.capture(new DigPacket(1), info),
            "capture requires sealed registration"
        );

        handlers.seal();
        rejects(
            ()->handlers.on(DigPacket.class, (packet, stamp)->1, value->{}),
            "registration stops before network observation"
        );

        DigPacket packet = new DigPacket(7);
        Runnable delivery = handlers.capture(packet, info);
        check(first.digs == 0 && second.digs == 0, "capture does not run server receivers");

        packet.value = 99;
        delivery.run();
        check(first.digs == 7 && second.digs == 7, "copied data survives later packet mutation");

        handlers.capture(new AttackPacket(), info).run();
        check(first.attacks == 1 && second.attacks == 1, "multiple collectors receive the same packet type");
        check(first.digs == 7, "one collector handles several observation families");
        check(handlers.capture(new Object(), info) == null, "unregistered packets are ignored");
        check(handlers.capture(new OtherDigPacket(), info) == null, "matching uses explicit packet classes");

        PacketHandlers otherSession = new PacketHandlers();
        Probe independent = new Probe();
        independent.registerHandlers(otherSession);
        otherSession.seal();
        otherSession.capture(new DigPacket(2), info).run();
        check(independent.digs == 2 && first.digs == 7, "sessions do not share collector state");

        first.reset("observation_discontinuity");
        check(first.digs == 0 && first.attacks == 0 && second.digs == 7, "reset affects only its collector");

        PacketHandlers selective = new PacketHandlers();
        List<Integer> calls = new ArrayList<>();
        selective.on(DigPacket.class, (value, stamp)->null, ignored->calls.add(1));
        selective.on(DigPacket.class, (value, stamp)->value.value, value->calls.add(value));
        selective.seal();
        selective.capture(new DigPacket(2), info).run();
        check(calls.size() == 1 && calls.get(0) == 2, "null copies do not suppress other collectors");

        PacketHandlers ignored = new PacketHandlers();
        ignored.on(DigPacket.class, (value, stamp)->null, value->{});
        ignored.seal();
        check(ignored.capture(new DigPacket(1), info) == null, "no work queued when all collectors ignore a packet");

        AtomicReference<PacketInfo> receivedInfo = new AtomicReference<>();
        AtomicReference<Thread> copyThread = new AtomicReference<>();
        AtomicReference<Thread> receiverThread = new AtomicReference<>();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        PacketHandlers threaded = new PacketHandlers();
        threaded.on(
            DigPacket.class,
            (value, stamp)->{
                copyThread.set(Thread.currentThread());
                return stamp;
            },
            stamp->{
                receivedInfo.set(stamp);
                receiverThread.set(Thread.currentThread());
            }
        );
        threaded.seal();

        Thread network = new Thread(()->queued.set(threaded.capture(new DigPacket(1), info)));
        network.start();
        network.join();
        queued.get().run();
        check(copyThread.get() == network, "packet copy runs on the capturing thread");
        check(receiverThread.get() == Thread.currentThread(), "receiver runs on the dispatching thread");
        check(
            receivedInfo.get().sequence == 10 && receivedInfo.get().readBatch == 4
                && receivedInfo.get().observedNanos == 123000000L && receivedInfo.get().epochMillis == 456L,
            "packet order, read cycle, and timestamps survive handoff"
        );

        System.out.println(passed + " packet routing checks passed");
    }
}
