#include "anticheat/builtins.hpp"
#include "anticheat/engine.hpp"
#include "fastbreak.hpp"
#include "wire.hpp"
#include <cmath>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

namespace{
void require(bool condition, const std::string& message){
    if(!condition){ throw std::runtime_error(message); }
}
std::size_t alerts(const std::vector<ac::Finding>& records){
    std::size_t count{};
    for(const auto& record : records){ if(record.level == "suspicious"){ ++count; } }
    return count;
}
struct Lab{
    ac::Engine engine;
    std::uint64_t ordinal{}, packet{}, time = 1000000000ULL;
    explicit Lab(std::string config = "fastbreak.alert_after=1")
        : engine(make(config)){
        send(ac::SessionStart{"11111111-1111-1111-1111-111111111111", 47, 10808});
    }
    static ac::Engine make(const std::string& config){
        auto setup = ac::builtin_checks(config);
        return ac::Engine(setup.trace, std::move(setup.factories));
    }
    std::vector<ac::Finding> send(ac::Payload payload){
        return engine.process({{1, ++ordinal, time, 1750000000000ULL, ordinal}, std::move(payload)});
    }
    ac::DigEvent dig(ac::DigAction action){
        return {++packet, packet, time, action, 1, {-10, 64, 15},
            {"world", "stone|hand|ground", "STONE", "AIR", "", 0.02, true}};
    }
    void start(){ send(dig(ac::DigAction::start)); }
    std::vector<ac::Finding> finish(std::uint64_t duration = 100000000ULL){
        time += duration; return send(dig(ac::DigAction::finish));
    }
};
struct Probe final : ac::Check{
    std::shared_ptr<int> hits;
    explicit Probe(std::shared_ptr<int> p) : hits(std::move(p)){}
    std::string_view id() const noexcept override{ return "test.multistream"; }
    void subscribe(ac::CheckManager& manager) override{
        manager.on<ac::TickEvent>([this](const ac::TickEvent&, ac::CheckContext&){ ++*hits; });
        manager.on<ac::DigEvent>([this](const ac::DigEvent&, ac::CheckContext&){ *hits += 10; });
    }
    void reset(std::string_view) override{}
};
void test(const char* name, void(*fn)(), int& passed){ fn(); ++passed; std::cout << "PASS " << name << '\n'; }
}
int main(){
    int passed{};
    try{
        test("early finish reports suspicious request", []{
            Lab lab; lab.start(); auto f = lab.finish();
            require(alerts(f) == 1, "missing alert");
            auto json = ac::to_json(f.back());
            require(json.find("NOT_MEASURED") != std::string::npos, "outcome invented");
            require(json.find("start_packet") != std::string::npos, "missing source references");
        }, passed);
        test("normal mining does not alert", []{ Lab l; l.start(); require(alerts(l.finish(2600000000ULL)) == 0, "normal flagged"); }, passed);
        test("finish without start", []{ Lab l; require(alerts(l.finish()) == 0, "invented start"); }, passed);
        test("abort resets attempt", []{ Lab l; l.start(); l.send(l.dig(ac::DigAction::abort)); require(alerts(l.finish()) == 0, "abort ignored"); }, passed);
        test("explicit reset", []{ Lab l; l.start(); l.send(ac::ResetEvent{"teleport"}); require(alerts(l.finish()) == 0, "reset ignored"); }, passed);
        test("mismatched block", []{
            Lab l; l.start(); l.time += 100000000; auto end = l.dig(ac::DigAction::finish); ++end.position.x;
            require(alerts(l.send(end)) == 0, "mismatched target flagged");
        }, passed);
        test("changed world", []{
            Lab l; l.start(); l.time += 100000000; auto end = l.dig(ac::DigAction::finish); end.context.world_uuid = "other";
            require(alerts(l.send(end)) == 0, "world mismatch");
        }, passed);
        test("changed tool/context", []{
            Lab l; l.start(); l.time += 100000000; auto end = l.dig(ac::DigAction::finish); end.context.state_key = "pick";
            require(alerts(l.send(end)) == 0, "changed context");
        }, passed);
        test("intermediate context change resets", []{
            Lab l; l.start(); auto d = l.dig(ac::DigAction::start); d.context.damage_per_tick = 0.03;
            l.send(ac::MiningContextEvent{d.position, d.context});
            require(alerts(l.finish()) == 0, "intermediate change ignored");
        }, passed);
        test("same network read batch excluded", []{
            Lab l; l.start(); l.time += 100000000; auto end = l.dig(ac::DigAction::finish); end.read_batch = 1;
            require(alerts(l.send(end)) == 0, "same batch flagged");
        }, passed);
        test("queue delay excluded", []{
            Lab l; auto start = l.dig(ac::DigAction::start); start.sampled_ns += 500000000; l.send(start);
            require(alerts(l.finish()) == 0, "delayed context flagged");
        }, passed);
        test("instant mining excluded", []{
            Lab l; auto d = l.dig(ac::DigAction::start); d.context.damage_per_tick = 1; l.send(d);
            require(alerts(l.finish()) == 0, "instant flagged");
        }, passed);
        test("nonfinite damage excluded", []{
            Lab l; auto d = l.dig(ac::DigAction::start); d.context.damage_per_tick = std::numeric_limits<double>::quiet_NaN(); l.send(d);
            require(alerts(l.finish()) == 0, "nan accepted");
        }, passed);
        test("unavailable context excluded", []{
            Lab l; auto d = l.dig(ac::DigAction::start); d.context.available = false; l.send(d);
            require(alerts(l.finish()) == 0, "missing context accepted");
        }, passed);
        test("backwards observation time", []{
            Lab l; l.start(); l.time -= 500000000;
            require(alerts(l.send(l.dig(ac::DigAction::finish))) == 0, "backwards time flagged");
        }, passed);
        test("expired attempt", []{
            Lab l; l.start(); l.time += 61000000000ULL; l.send(ac::TickEvent{});
            require(alerts(l.finish()) == 0, "attempt did not expire");
        }, passed);
        test("duplicate finish cannot duplicate finding", []{
            Lab l; l.start(); require(alerts(l.finish()) == 1, "expected first alert");
            require(alerts(l.finish()) == 0, "duplicate alert");
        }, passed);
        test("event gap resets", []{
            Lab l; l.start(); ++l.ordinal; require(alerts(l.finish()) == 0, "gap ignored");
        }, passed);
        test("duplicate ordinal discards and resets", []{
            Lab l; l.start(); --l.ordinal; l.send(ac::TickEvent{});
            require(alerts(l.finish()) == 0, "duplicate ordinal ignored");
        }, passed);
        test("three samples required", []{
            Lab l("fastbreak.alert_after=3");
            for(int i = 0; i < 3; ++i){ l.start(); require(alerts(l.finish()) == (i == 2 ? 1U : 0U), "wrong accumulation"); l.time += 500000000; }
        }, passed);
        test("normal sample clears accumulation", []{
            Lab l("fastbreak.alert_after=2"); l.start(); l.finish();
            l.start(); l.finish(3000000000ULL); l.start(); require(alerts(l.finish()) == 0, "normal did not clear");
        }, passed);
        test("sample window expires", []{
            Lab l("fastbreak.alert_after=2\nfastbreak.sample_window_ms=1000");
            l.start(); l.finish(); l.time += 2000000000ULL; l.start(); require(alerts(l.finish()) == 0, "window did not expire");
        }, passed);
        test("session isolation", []{
            Lab l; l.start(); auto payload = l.dig(ac::DigAction::finish);
            l.engine.process({{2, 1, l.time, 0, 0}, ac::SessionStart{"other", 47, 10808}});
            require(alerts(l.engine.process({{2, 2, l.time + 100000000, 0, 0}, payload})) == 0, "cross player join");
            require(alerts(l.finish()) == 1, "other player changed first player");
        }, passed);
        test("late data cannot recreate closed session", []{
            Lab l; l.start(); l.send(ac::SessionEnd{});
            require(l.engine.session_count() == 0, "session retained");
            require(l.finish().empty() && l.engine.session_count() == 0, "late data recreated session");
        }, passed);
        test("unsupported model is not evaluated", []{
            auto e = Lab::make("fastbreak.alert_after=1");
            e.process({{1, 1, 0, 0, 0}, ac::SessionStart{"player", 999, 999}});
            Lab l; auto a = l.dig(ac::DigAction::start); auto b = l.dig(ac::DigAction::finish);
            e.process({{1, 2, l.time, 0, 0}, a});
            require(alerts(e.process({{1, 3, l.time, 0, 0}, b})) == 0, "wrong model evaluated");
        }, passed);
        test("disabled check keeps engine operational", []{
            Lab l("fastbreak.enabled=false"); require(l.engine.session_count() == 1, "engine disabled");
            l.start(); require(alerts(l.finish()) == 0, "disabled check still ran");
        }, passed);
        test("generic multistream check coexists with FastBreak", []{
            auto hits = std::make_shared<int>(0);
            auto setup = ac::builtin_checks("fastbreak.alert_after=1");
            setup.factories.push_back([hits]{ return std::make_unique<Probe>(hits); });
            ac::Engine e(true, std::move(setup.factories));
            e.process({{1, 1, 0, 0, 0}, ac::SessionStart{"player", 47, 10808}});
            e.process({{1, 2, 0, 0, 0}, ac::TickEvent{}});
            Lab l; auto d = l.dig(ac::DigAction::start);
            e.process({{1, 3, l.time, 0, 0}, d});
            require(*hits == 11, "generic type dispatch failed");
        }, passed);
        test("invalid/unknown configuration rejected", []{
            for(const char* config : {"fastbreak.maximum_ratio=2", "fastbreak.alert_after=0", "unknown=3", "trace=maybe", "trace=true\ntrace=false"}){
                bool threw{}; try{ (void)ac::builtin_checks(config); }catch(const std::invalid_argument&){ threw = true; }
                require(threw, "invalid configuration accepted");
            }
        }, passed);
        test("finding JSON escaping", []{
            ac::Finding f{"trace", "x", "y", {}, "quote\"\n", {{"k", "\\"}}};
            auto json = ac::to_json(f);
            require(json.find("\\\"") != std::string::npos && json.find("\\u000a") != std::string::npos, "invalid JSON escaping");
        }, passed);
        test("valid bridge header and exact length", []{
            std::vector<std::uint8_t> b;
            auto add = [&](std::uint64_t v, int n){ for(int i = 0; i < n; ++i){ b.push_back(static_cast<std::uint8_t>(v >> (i * 8))); } };
            add(0x43415846, 4); add(1, 2); add(4, 2);
            add(7, 8); add(8, 8); add(9, 8); add(10, 8); add(11, 8);
            auto event = ac::decode_event(b.data(), b.size());
            require(event.header.session == 7 && event.header.server_tick == 11
                && std::holds_alternative<ac::TickEvent>(event.payload), "wire decode mismatch");
            for(std::size_t i = 0; i < b.size(); ++i){
                bool threw{}; try{ ac::decode_event(b.data(), i); }catch(const std::invalid_argument&){ threw = true; }
                require(threw, "truncated event accepted");
            }
            b.push_back(0); bool threw{};
            try{ ac::decode_event(b.data(), b.size()); }catch(const std::invalid_argument&){ threw = true; }
            require(threw, "trailing byte accepted");
        }, passed);
        test("bounded random malformed bridge inputs", []{
            std::mt19937 rng(42);
            for(int i = 0; i < 10000; ++i){
                std::vector<std::uint8_t> b(1 + rng() % 400);
                for(auto& byte : b){ byte = static_cast<std::uint8_t>(rng()); }
                if(i % 2 == 0 && b.size() >= 8){
                    b[0] = 'F'; b[1] = 'X'; b[2] = 'A'; b[3] = 'C'; b[4] = 1; b[5] = 0; b[6] = static_cast<std::uint8_t>(1 + i % 6); b[7] = 0;
                }
                try{ (void)ac::decode_event(b.data(), b.size()); }catch(const std::invalid_argument&){}
            }
        }, passed);
        std::cout << passed << " native tests passed\n";
        return 0;
    }catch(const std::exception& error){
        std::cerr << "FAIL after " << passed << " tests: " << error.what() << '\n'; return 1;
    }
}
