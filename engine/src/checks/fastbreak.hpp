#pragma once
#include "anticheat/check.hpp"
#include <optional>

namespace ac{
struct FastBreakSettings{
    double maximum_ratio = 0.65;
    double grace_ms = 100.0;
    double minimum_expected_ms = 250.0;
    double maximum_queue_ms = 200.0;
    std::uint32_t alert_after = 3;
    double sample_window_ms = 30000.0;
};
class FastBreakCheck final : public Check{
    struct Attempt{ EventHeader header; DigEvent dig; };
    FastBreakSettings settings_;
    std::optional<Attempt> attempt_;
    std::uint32_t samples_{};
    std::optional<std::uint64_t> last_sample_;
    void on_dig(const DigEvent& event, CheckContext& ctx);
    void on_context(const MiningContextEvent& event, CheckContext& ctx);
    void on_tick(const TickEvent&, CheckContext& ctx);
public:
    explicit FastBreakCheck(FastBreakSettings settings);
    std::string_view id() const noexcept override{ return "fastbreak.request.v1"; }
    void subscribe(CheckManager& manager) override;
    void reset(std::string_view reason) override;
};
}
