#include "wire.hpp"
#include <cstring>
#include <limits>
#include <stdexcept>

namespace ac{
namespace{
class Reader{
    const std::uint8_t* data_;
    std::size_t size_, offset_{};
public:
    Reader(const std::uint8_t* data, std::size_t size) : data_(data), size_(size){
        if(!data || size > 8192){ throw std::invalid_argument("Invalid event buffer"); }
    }
    std::uint64_t u(unsigned count){
        if(count > 8 || size_ - offset_ < count){ throw std::invalid_argument("Truncated event"); }
        std::uint64_t result{};
        for(unsigned i = 0; i < count; ++i){ result |= std::uint64_t(data_[offset_++]) << (i * 8); }
        return result;
    }
    std::int32_t i32(){
        auto value = u(4);
        std::int64_t signed_value = value <= 0x7fffffffULL
            ? static_cast<std::int64_t>(value) : static_cast<std::int64_t>(value) - 0x100000000LL;
        return static_cast<std::int32_t>(signed_value);
    }
    double f64(){
        static_assert(sizeof(double) == 8 && std::numeric_limits<double>::is_iec559);
        auto bits = u(8); double value{};
        std::memcpy(&value, &bits, sizeof(value)); return value;
    }
    std::string text(){
        auto length = static_cast<std::size_t>(u(2));
        if(length > 1024 || size_ - offset_ < length){ throw std::invalid_argument("Invalid string length"); }
        std::string value;
        value.reserve(length);
        for(std::size_t i = 0; i < length; ++i){
            auto c = static_cast<unsigned char>(u(1));
            // This v1 schema only carries UUIDs and ASCII game identifiers, not names/chat.
            if(c < 32 || c > 126){ throw std::invalid_argument("Non-ASCII identifier"); }
            value += static_cast<char>(c);
        }
        return value;
    }
    BlockPosition position(){ return {i32(), i32(), i32()}; }
    MiningContext context(){
        MiningContext c;
        c.world_uuid = text(); c.state_key = text(); c.block = text(); c.tool = text();
        c.unavailable_reason = text(); c.damage_per_tick = f64();
        auto available = u(1);
        if(available > 1){ throw std::invalid_argument("Invalid context flag"); }
        c.available = available != 0; return c;
    }
    void finish(){
        if(offset_ != size_){ throw std::invalid_argument("Trailing event bytes"); }
    }
};
}
Event decode_event(const std::uint8_t* bytes, std::size_t size){
    Reader r(bytes, size);
    if(r.u(4) != 0x43415846 || r.u(2) != 1){
        throw std::invalid_argument("Unsupported bridge schema");
    }
    auto type = r.u(2);
    Event event;
    event.header = {r.u(8), r.u(8), r.u(8), r.u(8), r.u(8)};
    switch(type){
        case 1: event.payload = SessionStart{r.text(), static_cast<std::uint32_t>(r.u(4)), static_cast<std::uint32_t>(r.u(4))}; break;
        case 2: event.payload = SessionEnd{}; break;
        case 3: event.payload = ResetEvent{r.text()}; break;
        case 4: event.payload = TickEvent{}; break;
        case 5:{
            DigEvent dig;
            dig.packet_sequence = r.u(8); dig.read_batch = r.u(8); dig.sampled_ns = r.u(8);
            auto action = r.u(1); auto face = r.u(1);
            if(action > 2 || face > 5){ throw std::invalid_argument("Invalid digging enum"); }
            dig.action = static_cast<DigAction>(action); dig.face = static_cast<std::uint8_t>(face);
            dig.position = r.position(); dig.context = r.context(); event.payload = std::move(dig); break;
        }
        case 6:{
            MiningContextEvent context;
            context.position = r.position(); context.context = r.context();
            event.payload = std::move(context); break;
        }
        default: throw std::invalid_argument("Unknown bridge event type");
    }
    r.finish();
    return event;
}
}
