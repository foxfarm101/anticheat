/**
 * fastbreak.cpp implements the FastBreak deetection logic
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
        bool usable(const MiningContext& c){
            return c.available &&
                std::isfinite(c.damage_per_tick) &&
                c.damage_per_tick > 0 &&
                c.damage_per_tick < 1;
        }
        
        bool same(const MiningContext& a, const MiningContext& b){
            return usable(a) && usable(b) && a.world_uuid == b.world_uuid
                && a.state_key == b.state_key && a.damage_per_tick == b.damage_per_tick;
        }
        double elapsed_ms(std::uint64_t end, std::uint64_t start){
            return end >= start ? static_cast<double>(end - start) / 1e6 : -1.0;
        }
        std::string number(double value){
            std::ostringstream out;
            out.imbue(std::locale::classic());
            out << std::fixed << std::setprecision(3) << value;
            return out.str();
        }
        std::string position(const BlockPosition& p){
            return std::to_string(p.x) + "," + std::to_string(p.y) + "," + std::to_string(p.z);
        }
    }
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
        manager.on<DigEvent>([this](const DigEvent& event, CheckContext& ctx){ on_dig(event, ctx); });
        manager.on<MiningContextEvent>([this](const MiningContextEvent& event, CheckContext& ctx){ on_context(event, ctx); });
        manager.on<TickEvent>([this](const TickEvent& event, CheckContext& ctx){ on_tick(event, ctx); });
    }
    void FastBreakCheck::reset(std::string_view){
        attempt_.reset(); samples_ = 0; last_sample_.reset();
    }
    void FastBreakCheck::on_tick(const TickEvent&, CheckContext& ctx){
        if(attempt_ && (ctx.event.observed_ns < attempt_->header.observed_ns
            || elapsed_ms(ctx.event.observed_ns, attempt_->header.observed_ns) > 60000)){
            reset("expired");
        }
    }
    void FastBreakCheck::on_context(const MiningContextEvent& event, CheckContext& ctx){
        if(attempt_ && (!(attempt_->dig.position == event.position)
            || !same(attempt_->dig.context, event.context))){
            reset("context_changed");
            ctx.emit("trace", std::string(id()), "context_changed_reset");
        }
    }
    void FastBreakCheck::on_dig(const DigEvent& event, CheckContext& ctx){
        // Model selection belongs to the check, not to the generic engine.
        if(ctx.player.identity.client_protocol != 47 || ctx.player.identity.server_model != 10808){ return; }
        if(event.action == DigAction::abort){
            reset("abort");
            ctx.emit("trace", std::string(id()), "abort_reset");
            return;
        }
        double queued = elapsed_ms(event.sampled_ns, ctx.event.observed_ns);
        if(queued < 0 || queued > settings_.maximum_queue_ms){
            reset("delayed_observation"); return;
        }
        if(event.action == DigAction::start){
            if(attempt_){ reset("replaced_start"); }
            double expected = usable(event.context) ? std::ceil(1.0 / event.context.damage_per_tick) * 50.0 : 0.0;
            if(expected < settings_.minimum_expected_ms || expected > 60000 || expected <= 0){
                reset("unsupported");
                ctx.emit("trace", std::string(id()), "start_skipped",
                    {{"reason", event.context.unavailable_reason}});
                return;
            }
            attempt_ = Attempt{ctx.event, event};
            ctx.emit("trace", std::string(id()), "start",
                {{"position", position(event.position)}, {"block", event.context.block},
                {"tool", event.context.tool}, {"packet", std::to_string(event.packet_sequence)},
                {"expected_ms", number(expected)}});
            return;
        }
        if(!attempt_){
            ctx.emit("trace", std::string(id()), "finish_without_start"); return;
        }
        const auto begin = std::move(*attempt_);
        attempt_.reset();
        auto skip = [&](const char* reason){
            reset(reason); ctx.emit("trace", std::string(id()), "finish_skipped", {{"reason", reason}});
        };
        if(!(begin.dig.position == event.position) || event.packet_sequence <= begin.dig.packet_sequence){
            skip("mismatched_finish"); return;
        }
        if(!same(begin.dig.context, event.context)){
            skip("changed_context"); return;
        }
        if(event.read_batch <= begin.dig.read_batch){
            skip("same_or_invalid_read_batch"); return;
        }
        double observed = elapsed_ms(ctx.event.observed_ns, begin.header.observed_ns);
        double sampled = elapsed_ms(event.sampled_ns, begin.dig.sampled_ns);
        if(observed < 0 || sampled < 0 || observed > 60000
            || std::abs(observed - sampled) > settings_.maximum_queue_ms){
            skip("uncertain_timing"); return;
        }
        double expected = std::ceil(1.0 / begin.dig.context.damage_per_tick) * 50.0;
        double threshold = std::max(0.0, expected * settings_.maximum_ratio - settings_.grace_ms);
        bool suspicious = observed < threshold;
        Evidence evidence{
            {"start_event", std::to_string(begin.header.ordinal)},
            {"start_packet", std::to_string(begin.dig.packet_sequence)},
            {"finish_packet", std::to_string(event.packet_sequence)},
            {"start_observed_ns", std::to_string(begin.header.observed_ns)},
            {"world", event.context.world_uuid}, {"position", position(event.position)},
            {"block", event.context.block}, {"tool", event.context.tool},
            {"observed_ms", number(observed)}, {"expected_ms", number(expected)},
            {"threshold_ms", number(threshold)}, {"server_break_outcome", "NOT_MEASURED"}
        };
        ctx.emit("trace", std::string(id()), suspicious ? "early_finish_sample" : "finish_no_flag", evidence);
        if(!suspicious){ samples_ = 0; last_sample_.reset(); return; }
        if(last_sample_ && (ctx.event.observed_ns < *last_sample_
            || elapsed_ms(ctx.event.observed_ns, *last_sample_) > settings_.sample_window_ms)){
            samples_ = 0;
        }
        last_sample_ = ctx.event.observed_ns;
        if(++samples_ >= settings_.alert_after){
            evidence.emplace_back("samples", std::to_string(samples_));
            ctx.emit("suspicious", std::string(id()), "repeated_early_completion_requests", std::move(evidence));
            samples_ = 0;
        }
    }

}