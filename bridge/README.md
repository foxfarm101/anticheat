# JNI/event boundary (v1)

`NativeBridge.nCreate(config)` creates an engine and returns a checked numeric ID.
`nSubmit(handle, directByteBuffer, length)` synchronously consumes one normalized
event and returns ASCII JSON report strings (or null for none).
`nDestroy(handle)` destroys that engine and all per-session checks.

The buffer belongs to Java. Native code decodes and copies values during the call;
it retains no pointer into the Java buffer, no JNI references, and no JNIEnv.
All calls use the creating thread. Registry access is also mutex-protected.
The Java wrapper guards closed handles and thread misuse. Native entry points
validate independently and translate C++ exceptions into Java exceptions.
Native faults such as access violations are NOT recoverable through this mechanism.

This is our own transport representation of normalized events, not raw Minecraft
packets. There is no packed struct cast: the reader checks each field explicitly.
All scalars are little endian. Text is `u16 length + ASCII bytes`, maximum 1024
bytes. Events are capped at 8192 bytes. Unknown schemas/types, trailing bytes,
truncation, invalid booleans/enums, and non-ASCII identifiers are rejected.
This restricted text encoding is for UUIDs/game identifiers, not player chat.

## Header (48 bytes)

```
u32 magic = 0x43415846 (bytes "FXAC")
u16 schema = 1
u16 type
u64 session_id
u64 normalized_event_ordinal
u64 observed_nanoseconds_since_adapter_start
u64 epoch_milliseconds
u64 server_tick
```

Ordinal is incremented at delivery on the main thread; packet sequence is recorded
at the decoded Netty observation point. They are deliberately distinct. Tick time
is a snapshot, not an assertion that wall-clock intervals equal a number of ticks.

## Bodies

```
1 SessionStart:
    text uuid,
    u32 client_protocol,
    u32 server_model

2 SessionEnd:
    empty

3 Reset:
    text reason

4 Tick:
    empty

5 Dig:
    u64 packet_sequence,
    u64 read_batch,
    u64 sampled_ns,
    u8 action (0=start, 1=abort, 2=finish),
    u8 face (0..5),
    i32 x,
    i32 y,
    i32 z,
    MiningContext

6 Context:
    i32 x,
    i32 y,
    i32 z,
    MiningContext

MiningContext:
    text world_uuid, text state_key, text block, text tool,
    text unavailable_reason, f64 damage_per_tick, u8 available
```

The server-model value 10808 is an internal adapter ID.
Protocol 47 is the configured direct 1.8.x baseline; this adapter does not discover clients hidden behind a translator or distinguish 1.8.x patches sharing a protocol.

When adding telemetry, update the typed event and both schema ends together and add a cross-language smoke test. The public Check interface and generic dispatcher do not change. Batched transfers or an out-of-process transport can be added later; this starter makes one synchronous call per normalized observation.