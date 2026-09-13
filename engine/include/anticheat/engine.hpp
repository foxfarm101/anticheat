/**
 * engine.hpp defines the detection engine that manages player sessions,
 * processes normalized observations, and dispatches them to their checks.
 */

#pragma once
#include "check.hpp"
#include <memory>
#include <unordered_map>

namespace ac{

    // Detection engine is independent of Minecraft and Java integration
    class Engine{
        // Detection state and checks belonging to one player session
        struct Session{
            PlayerState state;
            CheckManager checks;
        };
        
        bool trace_;
        std::vector<CheckFactory> factories_;

        // Active player sessions indexed by their session ID
        std::unordered_map<SessionId, std::unique_ptr<Session>> sessions_;
    public:
        Engine(bool trace, std::vector<CheckFactory> factories);

        // Process one observation and return any findings it produces
        std::vector<Finding> process(const Event& event);

        // Number of currently active player sessions
        std::size_t session_count() const noexcept{
            return sessions_.size();
        }
    };

}