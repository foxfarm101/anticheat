/**
 * fastbreak.hpp contains the FastBreak detector.
 * It defines:
 *   . the thresholds used to identify suspicious mining behavior
 *   . the state needed to track mining attempts
 *   . the functions that evaluate incoming mining observations
 */

#pragma once
#include "anticheat/check.hpp"
#include <optional>

namespace ac{

    // Configurable thresholds and time limits used by the FastBreak detector
    struct FastBreakSettings{
        double maximum_ratio       = 0.65;
        double grace_ms            = 100.0;
        double minimum_expected_ms = 250.0;
        double maximum_queue_ms    = 200.0;
        std::uint32_t alert_after  = 3;
        double sample_window_ms    = 30000.0; // max time suspicious samples can remain grouped toward alert_after
    };

    // Detects mining attempts that finish faster than expected
    class FastBreakCheck final : public Check{
        // Store the beginning of the mining attempt currently being tracked
        struct Attempt{
            EventHeader header;
            DigEvent dig;
        };

        FastBreakSettings settings_;

        // Active mining attempt, if one is currently being tracked.
        std::optional<Attempt> attempt_;

        // Recent suspicious samples
        // Used to determine when to emit a finding
        std::uint32_t samples_{};
        // Time of the most recent suspicious sample
        std::optional<std::uint64_t> last_sample_;
        
        // Process client digging observations
        void on_dig(const DigEvent& event, CheckContext& ctx);

        // Process changes to the server-side conditions affecting the active attempt
        void on_context(const MiningContextEvent& event, CheckContext& ctx);

        // Clear suspicious FastBreak samples when the configured time window expires
        void on_tick(const TickEvent&, CheckContext& ctx);

    public:
        explicit FastBreakCheck(FastBreakSettings settings);
        
        // Unique ID for this detector and its current detection logic version
        std::string_view id() const noexcept override{
            return "fastbreak.request.v1";
        }
        
        // Register the observation handlers for the FastBreak detector
        void registerHandlers(CheckManager& manager) override;

        // Clear the currently tracked attempt and suspicious sample history
        void reset(std::string_view reason) override;
    };

}