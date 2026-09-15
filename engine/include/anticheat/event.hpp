/**
 * event.hpp contains the observations that go into the detection engine
 */

#pragma once
#include <cstdint>
#include <string>
#include <variant>

namespace ac{

    using SessionId = std::uint64_t;

    // Shared identity, ordering, and timing metadata.
    // Monotonic timestamps use the Java adapter's origin, not C++'s steady_clock.
    struct EventHeader{
        SessionId session{};         // ID for the player session this observation belongs to
        std::uint64_t ordinal{};     // Delivery order of normalized events
        std::uint64_t observed_ns{}; // Time when the Java adapter first observed the incoming packet
        std::uint64_t epoch_ms{};
        std::uint64_t server_tick{};
    };

    // Identifies the player and version context for a new session
    struct SessionStart{
        std::string player_uuid;
        std::uint32_t client_protocol{};
        std::uint32_t server_model{}; // our detection model ID for Spigot 1.8.8: 10808
    };

    struct SessionEnd{}; // end of player session

    // Requests a history reset when prior observations are not longer reliable
    // EXAMPLE:
    //  ResetEvent is used to clear player mining data because the adapter's queue overflowed and the engine
    //  saw a mining start but missed its cancellation. Later observations won't be evaluated against a mining
    //  attempt that already ended.
    struct ResetEvent{
        std::string reason;
    };

    // Marks a server tick for periodic check processing
    struct TickEvent{};

    // Integer block coords within a world
    struct BlockPosition{
        std::int32_t x{}, y{}, z{};
        bool operator==(const BlockPosition& other) const noexcept{
            return x == other.x && y == other.y && z == other.z;
        }
    };
    
    // Snapshot of server-side mining conditions, or why they are unavailable.
    struct MiningContext{
        std::string world_uuid;
        std::string state_key;
        std::string block;
        std::string tool;
        std::string unavailable_reason;
        double damage_per_tick{};
        bool available{};
    };

    // Mining action claimed by the client
    enum class DigAction : std::uint8_t{
        start,
        abort,
        finish
    };

    // Observed digging request with server context
    struct DigEvent{
        std::uint64_t packet_sequence{}; // ID given to each packet decoded by the Java adapter
        std::uint64_t read_batch{};      // The packets Netty delivered to the server in one network read
        std::uint64_t sampled_ns{};      // Time when the Java adapter sampled (took a snapshot of) the relevant server state
        DigAction     action{};
        std::uint8_t  face{};
        BlockPosition position;
        MiningContext context;
    };

    // Dynamically updated mining context collected independently of a digging packet
    struct MiningContextEvent{
        BlockPosition position;
        MiningContext context;
    };

    // Supported event types; new observations are added here (not to the Check interface)
    using Payload = std::variant<SessionStart, SessionEnd, ResetEvent, TickEvent,
                                DigEvent, MiningContextEvent>;

    // Pairs shared metadata with one typed event payload
    struct Event{
        EventHeader header; // observation metadata
        Payload payload;    // typed observation data
    };

}