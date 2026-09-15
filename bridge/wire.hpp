#pragma once
#include "anticheat/event.hpp"
#include <cstddef>
#include <cstdint>

namespace ac{
    Event decode_event(const std::uint8_t* bytes, std::size_t size);
}