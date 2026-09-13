#include "anticheat/engine.hpp"
#include <stdexcept>

namespace ac{
Engine::Engine(bool trace, std::vector<CheckFactory> factories)
    : trace_(trace), factories_(std::move(factories)){}

std::vector<Finding> Engine::process(const Event& event){
    if(event.header.session == 0 || event.header.ordinal == 0){
        throw std::invalid_argument("Zero session or event ordinal");
    }
    std::vector<Finding> output;
    if(const auto* begin = std::get_if<SessionStart>(&event.payload)){
        if(sessions_.count(event.header.session)){
            throw std::invalid_argument("Session already open");
        }
        if(sessions_.size() >= 4096 || begin->player_uuid.empty()){
            throw std::invalid_argument("Invalid session or session capacity reached");
        }
        auto session = std::make_unique<Session>();
        session->state = {event.header.session, *begin, event.header.ordinal};
        for(const auto& factory : factories_){ session->checks.add(factory()); }
        CheckContext ctx{session->state, event.header, trace_, output};
        ctx.emit("trace", "engine", "session_open");
        session->checks.dispatch(event, ctx);
        sessions_.emplace(event.header.session, std::move(session));
        return output;
    }
    auto found = sessions_.find(event.header.session);
    // Late callbacks after a disconnect cannot create a new session.
    if(found == sessions_.end()){ return output; }
    auto& session = *found->second;
    CheckContext ctx{session.state, event.header, trace_, output};
    if(event.header.ordinal <= session.state.last_ordinal){
        session.checks.reset("duplicate_or_out_of_order");
        ctx.emit("trace", "engine", "discarded_duplicate_or_out_of_order");
        return output;
    }
    if(event.header.ordinal - session.state.last_ordinal != 1){
        session.checks.reset("event_gap");
        ctx.emit("trace", "engine", "event_gap_reset");
    }
    session.state.last_ordinal = event.header.ordinal;
    if(std::holds_alternative<SessionEnd>(event.payload)){
        ctx.emit("trace", "engine", "session_closed");
        sessions_.erase(found);
        return output;
    }
    if(const auto* reset = std::get_if<ResetEvent>(&event.payload)){
        session.checks.reset(reset->reason);
        ctx.emit("trace", "engine", "reset", {{"reason", reset->reason}});
    }
    session.checks.dispatch(event, ctx);
    return output;
}
}
