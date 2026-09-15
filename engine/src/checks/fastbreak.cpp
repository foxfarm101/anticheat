/**
 * fastbreak.cpp implements the FastBreak detection logic
 */

#include "fastbreak.hpp"
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <locale>
#include <sstream>
#include <stdexcept>

namespace ac{

    namespace{
        // Is this MiningContext usable for evaluating the mining attempt?
        bool usable(const MiningContext& c){
            return c.available &&                    // Java adapter successfully collected the mining context
                std::isfinite(c.damage_per_tick) &&  // Value is a normal finite number, not positive or negative infinity
                c.damage_per_tick > 0 &&             // Player is capable of making mining progress
                c.damage_per_tick < 1;               // Block takes more than one increment of mining progress (>=1 represents instant breaking behavior)
        }
        
        // Did this MiningContext remain unchanged between DigAction::start and DigAction::finish?
        bool same(const MiningContext& a, const MiningContext& b){
            return usable(a) &&
                   usable(b) &&
                   a.world_uuid == b.world_uuid &&
                   a.state_key == b.state_key &&
                   a.damage_per_tick == b.damage_per_tick;
        }

        // Calculate elapsed milliseconds between DigAction::start and DigAction::finish observations?
        double elapsed_ms(std::uint64_t end, std::uint64_t start){
            return end >= start ? static_cast<double>(end - start) / 1e6 : -1.0;
        }

        // Format a double as a string with 3 decimal places
        std::string number(double value){
            std::ostringstream out;
            out.imbue(std::locale::classic());
            out << std::fixed << std::setprecision(3) << value;
            return out.str();
        }

        // Format a BlockPosition as "x,y,z"
        std::string position(const BlockPosition& p){
            return std::to_string(p.x) + "," + std::to_string(p.y) + "," + std::to_string(p.z);
        }
    }

    // Construct a FastBreakCheck with its configuration and ensure the configuration is valid
    // : settings_(settings) initializes the entire settings_ member using the settings argument
    FastBreakCheck::FastBreakCheck(FastBreakSettings settings) : settings_(settings){
        if(!std::isfinite(settings.maximum_ratio) || settings.maximum_ratio <= 0
            || settings.maximum_ratio >= 1 || !std::isfinite(settings.grace_ms)
            || settings.grace_ms < 0 || !std::isfinite(settings.minimum_expected_ms)
            || settings.minimum_expected_ms < 0 || !std::isfinite(settings.maximum_queue_ms)
            || settings.maximum_queue_ms <= 0 || settings.alert_after == 0
            || settings.alert_after > 10000 || !std::isfinite(settings.sample_window_ms)
            || settings.sample_window_ms <= 0){
            throw std::invalid_argument("Invalid FastBreak settings");
        }
    }

    void FastBreakCheck::registerHandlers(CheckManager& manager){
        manager.on<DigEvent>([this](const DigEvent& event, CheckContext& ctx){
            on_dig(event, ctx);
        });
        manager.on<MiningContextEvent>([this](const MiningContextEvent& event, CheckContext& ctx){
            on_context(event, ctx);
        });
        manager.on<TickEvent>([this](const TickEvent& event, CheckContext& ctx){
            on_tick(event, ctx);
        });
    }

    // Clear the currently tracked attempt and suspicious sample history
    void FastBreakCheck::reset(std::string_view){
        attempt_.reset(); samples_ = 0;
        last_sample_.reset();
    }

    // Clear suspicious FastBreak samples when the configured time window expires
    void FastBreakCheck::on_tick(const TickEvent&, CheckContext& ctx){
        if(attempt_ && (ctx.event.observed_ns < attempt_->header.observed_ns
            || elapsed_ms(ctx.event.observed_ns, attempt_->header.observed_ns) > 60000)){
            reset("expired");
        }
    }

    // Process changes to the server-side conditions affecting the active attempt
    void FastBreakCheck::on_context(const MiningContextEvent& event, CheckContext& ctx){
        if(attempt_ && (!(attempt_->dig.position == event.position)
            || !same(attempt_->dig.context, event.context))){
            reset("context_changed");
            ctx.emit(
                "trace",
                std::string(id()),
                "context_changed_reset"
            );
        }
    }

    // Process client digging observations
    void FastBreakCheck::on_dig(const DigEvent& event, CheckContext& ctx){
        // Only evaluate minecraft client versions supported by this Spigot 1.8.8 FastBreak detection logic
        //  Minecraft version: 1.8.8 (protocol 47)
        //  Spigot server: 1.8.8 (server model 10808)
        if(ctx.player.identity.client_protocol != 47 /*1.8.x = protocol 47*/ || ctx.player.identity.server_model != 10808){
            return;
        }
        
        if(event.action == DigAction::abort){
            reset("abort");
            ctx.emit( // record that the abort caused the detector state to reset
                "trace",
                std::string(id()),
                "abort_reset"
            );
            return;
        }

        double queued = elapsed_ms(event.sampled_ns, ctx.event.observed_ns);

        // Reject late observations becausae the sampled server state won't match the state the packet had when it arrived
        if(queued < 0 || queued > settings_.maximum_queue_ms){
            reset("delayed_observation");
            return;
        }

        if(event.action == DigAction::start){
            if(attempt_){ // previous mining attempt is still being tracked
                reset("dig_replaced_start");
            }

            // Convert damage-per-tick into the expected mining duration under the current game conditions
            // damage_per_tick = how much block break progress is being made during each tick
            /**
             * Mining progress in vanilla minecraft goes from 0.0 (unbroken) to 1.0 (broken)
             * On each tick: progress += damage_per_tick
             * ex. damage_per_tick = 0.20:
             *   . Tick 1 -> 0.20
             *   . Tick 2 -> 0.40
             *   . Tick 3 -> 0.60
             *   . Tick 4 -> 0.80
             *   . Tick 5 -> 1.00 (broken)
             * 
             * Therefore, required ticks = ceil(1.0 / damage_per_tick).
             * @ 20 TPS, each tick = 50ms.
             */
            double expected = 0.0;
            if(usable(event.context)){
                expected = std::ceil(1.0 / event.context.damage_per_tick) * 50.0;
                // * 50.0 converts the required tick count to milliseconds, since 1 tick = 50ms.
                // FastBreak uses milliseconds when comparing expected and observed mining duration.
            }

            if(expected < settings_.minimum_expected_ms || expected > 60000 || expected <= 0){
                reset("unsupported");
                ctx.emit(
                    "trace",
                    std::string(id()),
                    "dig_start_skipped",
                    {
                        {"reason", event.context.unavailable_reason}
                    }
                );
                return;
            }

            // Begin tracking this mining attempt and trace its starting state
            attempt_ = Attempt{ctx.event, event};
            ctx.emit(
                "trace",
                std::string(id()),
                "dig_start",
                {
                    {"position", position(event.position)},
                    {"block", event.context.block},
                    {"tool", event.context.tool},
                    {"packet", std::to_string(event.packet_sequence)},
                    {"expected_ms", number(expected)}
                }
            );

            return;
        }

        if(!attempt_){
            ctx.emit(
                "trace",
                std::string(id()),
                "dig_finish_without_start"
            );
            return;
        }

        // Store the currently tracked mining attempt
        const auto begin = std::move(*attempt_);
        
        // detector no longer tracking an attempt
        attempt_.reset();

        auto skip = [&](const char* reason){
            reset(reason);
            ctx.emit(
                "trace",
                std::string(id()),
                "dig_finish_skipped",
                {
                    {"reason", reason}
                }
            );
        };

        if(!(begin.dig.position == event.position) || event.packet_sequence <= begin.dig.packet_sequence){
            skip("mismatched_finish");
            return;
        }

        if(!same(begin.dig.context, event.context)){
            skip("changed_context");
            return;
        }

        if(event.read_batch <= begin.dig.read_batch){
            skip("same_or_invalid_read_batch");
            return;
        }

        // packet observation time elapsed between DigAction::start and DigAction::finish
        double observed = elapsed_ms(ctx.event.observed_ns, begin.header.observed_ns);
        // server-state snapshot time elapsed between DigAction::start and DigAction::finish
        double sampled = elapsed_ms(event.sampled_ns, begin.dig.sampled_ns);
        if(observed < 0 || sampled < 0 || observed > 60000
            || std::abs(observed - sampled) > settings_.maximum_queue_ms){
            skip("uncertain_timing");
            return;
        }

        // Calculate the expected mine duration from the MiningContext conditions when the DigAction::start observation occurred
        double expected = std::ceil(1.0 / begin.dig.context.damage_per_tick) * 50.0;
        
        // Calculate how fast the attempt must finish to be considered suspicious
        double sus_threshold = std::max(0.0, expected * settings_.maximum_ratio - settings_.grace_ms);
        
        // If the observed mining duration is faster than the suspicious threshold, suspicious=true.
        bool suspicious = observed < sus_threshold;

        // Evidence for the Finding
        Evidence evidence{
            {"start_event", std::to_string(begin.header.ordinal)},
            {"start_packet", std::to_string(begin.dig.packet_sequence)},
            {"finish_packet", std::to_string(event.packet_sequence)},
            {"start_observed_ns", std::to_string(begin.header.observed_ns)},
            {"world", event.context.world_uuid},
            {"position", position(event.position)},
            {"block", event.context.block},
            {"tool", event.context.tool},
            {"observed_ms", number(observed)},
            {"expected_ms", number(expected)},
            {"sus_threshold_ms", number(sus_threshold)},
            {"server_break_outcome", "NOT_MEASURED"}
        };

        // Track the finished mining attempt and the detector's decision about it
        // If there are enough suspicious mining durations in a certain timespan, they can be escalated to an actual suspicious finding.
        ctx.emit(
            "trace",
            std::string(id()),
            suspicious ? "dig_early_finish_sample" : "dig_finish_normal_sample", evidence
        );

        if(!suspicious){
            samples_ = 0;
            last_sample_.reset();
            return;
        }

        if(last_sample_ && (ctx.event.observed_ns < *last_sample_
            || elapsed_ms(ctx.event.observed_ns, *last_sample_) > settings_.sample_window_ms)){
            samples_ = 0;
        }

        last_sample_ = ctx.event.observed_ns;

        if(++samples_ >= settings_.alert_after){
            evidence.emplace_back("samples", std::to_string(samples_));
            ctx.emit(
                "suspicious",
                std::string(id()),
                "repeated_early_completion_requests",
                std::move(evidence)
            );
            samples_ = 0;
        }
    }

}