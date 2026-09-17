# anticheat

Detection engine is written in C++, Java 8-compatible Spigot 1.8.8 is the adapter, in-process JNI bridge.

## Architecture

```
/anticheat

  engine/                              . C++ detection engine
    include/anticheat/
      event.hpp                        . normalized observation types
      finding.hpp                      . detection results and evidence
      check.hpp                        . check interface and CheckManager
      engine.hpp                       . central engine and player sessions
      builtins.hpp                     . built-in check creation interface

    src/
      engine.cpp                       . processes observations through the engine
      finding.cpp                      . serializes Findings for Java

      checks/
        exampleCheat.hpp               . specific cheat detector definition and state
        exampleCheat.cpp               . specific cheat detection logic
        builtins.cpp                   . configures and creates built-in checks

  bridge/                              . C++ side of Java <-> C++ communication
    jni.cpp                            . receives JNI calls from Java
    wire.hpp                           . observation decoding declarations
    wire.cpp                           . decodes serialized observations into C++ Events
    README.md                          . explains the Java <-> C++ data format

  plugin/                              . integration between our anticheat and the Minecraft/Spigot server
    src/main/
      java/dev/fox/anticheat/
        AntiCheatPlugin.java           . starts, stops, and connects plugin components
        Session.java                   . Java-side player session tracking

        packet/
          PacketObserver.java          . observes decoded Minecraft packets

        version/
          MiningSampler.java           . samples relevant Spigot/NMS mining state

        event/
          DigEvent.java                . Java digging observation
          MiningContext.java           . sampled mining-state snapshot

        bridge/                        . Java side of Java ↔ C++ communication
          EventWriter.java             . serializes observations into bytes
          NativeBridge.java            . calls the C++ engine through JNI

      resources/
        plugin.yml                     . tells Spigot how to load the plugin
        engine.conf                    . detection configuration

  tests/                               . automated testing
    engine_tests.cpp                   . tests C++ engine and detection behavior
    NativeSmokeTest.java               . tests Java → JNI → C++ → Java

  CMakeLists.txt                       . C++/JNI build configuration
  build.ps1                            . Windows build/package script
  .gitignore                           . files excluded from Git
  README.md                            . project documentation
```


## High level overview

1. Player starts mining block

2. Raw bytes for `DigAction::start` are sent to the server

3. Netty receives the raw bytes and decodes them into a `PacketPlayInBlockDig` packet

4. Java adapter observes the decoded packet

5. Java adapter takes a snapshot (sample) of the relevant server state (`MiningContext`)

6. Java adapter converts the `PacketPlayInBlockDig` packet + sampled server state (`MiningContext`) into the server-specific `DigEvent` format

7. JNI sends the serialized observation (`DigEvent` + its `EventHeader`) to C++ (`bridge/jni.cpp`)

8. `wire.cpp` decodes the serialized observation into the C++ `ac::Event` whose payload contains the C++ `DigEvent` struct

9. The detection engine (`engine.cpp`) processes the event via `Engine::process()`, which gets the player session via `EventHeader::session`, updates the session state, and creates `CheckContext`

10. `CheckManager` sends the `DigEvent` and its `CheckContext` to the checks that have handlers registered for `DigEvent` observation type

11. `FastBreakCheck` is one of those interested checks; it applies the FastBreak detection logic to that observation and evaluates suspiciousness

12. `Finding`(s) are returned to Java from C++ via JNI

### Detection logic

- `DigEvent` -> `FastBreakCheck::on_dig()`

- Track START/ABORT/FINISH

- Compare START and FINISH timing

- Calculate expected mining duration

- Determine whether the attempt is within a suspicious threshold

- Produce `Finding`(s)

## Boundaries

```
Java: decoded packets + main-thread server snapshots
  -> versioned, copied observations
  -> JNI bridge
  -> C++: per-session state -> typed check handler registrations -> findings
  -> Java: log returned findings
```

### Java plugin

Decode and analyze packets, collect snapshots of the server state, handle player connections and plugin lifecycle, and then log returned findings.

`/plugin/` contains the entire Java side of the anticheat, responsible for integrating Minecraft/Spigot with the C++ detection engine.

### JNI bridge

The JNI framework allows Java code running inside the JVM to call and be called by C++ code. This will allow us to transfer normalized observations into C++ and return results.

`/plugin/.../bridge/` contains specifically the Java <-> C++ communication code: serializing normalized observations and calling the C++ engine through JNI.

### C++ detection engine

Manages player sessions and state, routes observations to checks, and collects their findings.

`engine/` does not depend on JNI, Bukkit, or Netty. It can run in native tests without Java or Minecraft.

### C++ checks

Individual detection mechanisms within the engine that evaluate behavior and report supporting evidence. For instance, FastBreak checks whether the client claimed to finish mining at an impluasible speed.

`Check` and `CheckManager` are in `engine/include/anticheat/check.hpp`. Checks register handlers for any number of typed observations. Multiple checks may consume the same observation. Each connected session gets fresh check instances. `Finding` is generic; mining evidence is not required by its interface.

FastBreak registration/settings are in `engine/src/checks/builtins.cpp`, the composition root, not in `Engine` or `CheckManager`. Disabling FastBreak does not disable telemetry or the engine. The multistream test check demonstrates this structure; movement/combat collection is not implemented yet.

## Windows build

Requirements: x64 MSVC with C++17 support, CMake 3.24+, Ninja, a build JDK (11+), and `spigot-1.8.8.jar`.

**x64 Native Tools Command Prompt for Visual Studio**:

```bat
cd /d C:\anticheat-lab\anticheat
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Jdk "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

Build output:

```
build/libs/anticheat.jar
build/libs/anticheat_native.dll
```

The build first runs native tests and a Java-to-C++ JNI smoke test, then compiles the full adapter against your real server JAR.

### Deploy with Spigot STOPPED

Run `stop` in the server console first. From PowerShell in this source folder:

```powershell
New-Item -ItemType Directory -Force ..\server-1.8\plugins\FoxAntiCheat | Out-Null
Copy-Item .\build\libs\anticheat.jar ..\server-1.8\plugins\anticheat.jar -Force
Copy-Item .\build\libs\anticheat_native.dll ..\server-1.8\plugins\FoxAntiCheat\anticheat_native.dll -Force
```

Joining should also report `Packet observer attached: session=...`.
Mine ordinary stone in Survival while standing on solid ground. The default trace
prints JSON records for `start`, `finish_no_flag`, aborts, or skipped attempts.
Normal mining should NOT need to generate a suspicious finding to prove the
pipeline works. The synthetic tests exercise early completion requests.

Runtime settings are copied on first enable to:

```
server-1.8/plugins/FoxAntiCheat/engine.conf
```

Edit that copy, then restart.
Set `trace=false` to suppress ordinary trace records. Suspicious reports remain.

## Adding checks

To add a check using existing observations, implement `Check`, register handlers for the
needed types, register its factory in `builtins.cpp`, and add its source to CMake.
Do not modify `Check`, `CheckManager`, or another check.
For a new observation family, also extend the typed event/schema and adapter; that is collection work, not a redesign of the dispatcher.

## Standalone engine build

In a compiler-configured shell (no JDK needed):

```text
cmake -S . -B build/core -G Ninja -DAC_BUILD_JNI=OFF -DCMAKE_BUILD_TYPE=Debug
cmake --build build/core
ctest --test-dir build/core --output-on-failure
```

On Linux with a JDK and C++ compiler, the normal CMake build also builds the JNI
library and smoke test. It does not build the Spigot adapter; `build.ps1` does that
on Windows against your local server JAR.

## Scope and limitations

The C++ check reconstructs matching START/FINISH requests. Under a fixed sampled context it estimates `ceil(1 / damage_per_tick) * 50 ms`, then applies the configured ratio/grace threshold. Repeated short intervals can produce a **suspicious request** finding. All duration comparisons and verdicts are native code.

The Java version adapter samples the existing NMS mining-strength primitive;
this is not a full client simulator. The check discards changed or unavailable
contexts, missing starts, aborted attempts, observation gaps, stale samples, and
same-read-batch comparisons. Periodic context snapshots are not continuous
observation of every game-state transition. Receive timing is not client timing.
No inference is made about whether the server actually removed the block.
There are false-negative paths and unmeasured false-positive rates. No probability
or production accuracy claim is attached to the heuristic.

The networking callback only copies fields and queues bounded work. World reads
and JNI calls occur on the main server thread. The queue-before-forward ordering
is specifically for the pinned 1.8.8 implementation and must be re-audited for new
versions. Native handles are checked registry IDs, not exposed pointers. JNI
exceptions are contained at entry points. A native access violation can STILL
terminate the JVM; this is not crash isolation.

Do not run expensive model training, disk IO, or unbounded analysis on this live
synchronous path. This version has no dataset importer, ML model, dashboard,
movement/combat collection, or punishment system yet.

- https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/design.html
- https://docs.oracle.com/en/java/javase/21/docs/specs/man/javac.html
- https://netty.io/4.0/api/io/netty/channel/ChannelPipeline.html
- https://hub.spigotmc.org/javadocs/spigot/org/bukkit/scheduler/BukkitScheduler.html
