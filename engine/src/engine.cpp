/**
 * engine.cpp contains the detection engine implementation
 * 
 * Event enters Engine::process()
 *  checks process the observation
 *   ctx.emit() appends Findings to output
 *    return output
 *     caller (JNI bridge for the server plugin) receives vector<Finding>
 */

#include "anticheat/engine.hpp"
#include <stdexcept>

namespace ac{

    Engine::Engine(bool trace, std::vector<CheckFactory> factories) : trace_(trace), factories_(std::move(factories)){}

    // Give the detection engine the current Event and process it for this player session and
    // perform the checks on it, then return any Findings produced.
    std::vector<Finding> Engine::process(const Event& event){
        if(event.header.session == 0 || event.header.ordinal == 0){
            throw std::invalid_argument("Zero session or event ordinal");
        }

        std::vector<Finding> output;
        
        // If this observation starts a new player session, get a pointer to its SessionStart data
        if(const auto* begin = std::get_if<SessionStart>(&event.payload)){
            if(sessions_.count(event.header.session)){
                throw std::invalid_argument("Session already open");
            }

            if(sessions_.size() >= 4096 || begin->player_uuid.empty()){
                throw std::invalid_argument("Invalid session or session capacity reached");
            }

            auto session = std::make_unique<Session>();
            
            session->state = {
                event.header.session,
                *begin,
                event.header.ordinal
            };

            for(const auto& factory : factories_){
                session->checks.add(factory());
            }

            CheckContext ctx{
                session->state,
                event.header,
                trace_, output
            };

            ctx.emit(
                "trace",
                "engine",
                "session_open"
            );

            // send the observation to all checks that have handlers registered for its type
            session->checks.dispatch(event, ctx);

            // add the newly created player session to the detection engine's sessions_ map
            sessions_.emplace(event.header.session, std::move(session));

            return output;
        }

        // Get the player session associated with this observation's session ID
        auto found = sessions_.find(event.header.session);
        // Late callbacks after a disconnect cannot create a new session
        if(found == sessions_.end()) return output;
        auto& session = *found->second; // get the value (second), not the key (first).

        CheckContext ctx{
            session.state,
            event.header,
            trace_,
            output
        };

        // Reject event if its ordinal is not newer than the event that was processed last
        if(event.header.ordinal <= session.state.last_ordinal){
            session.checks.reset("duplicate_or_out_of_order");
            ctx.emit(
                "trace",
                "engine",
                "discarded_duplicate_or_out_of_order"
            );
            return output;
        }

        // Detect one or more observations being lost
        if(event.header.ordinal - session.state.last_ordinal != 1){
            session.checks.reset("event_gap");
            ctx.emit(
                "trace",
                "engine",
                "event_gap_reset"
            );
        }

        // update session, now this is the most recent observation
        session.state.last_ordinal = event.header.ordinal;

        // end session
        if(std::holds_alternative<SessionEnd>(event.payload)){
            ctx.emit(
                "trace",
                "engine",
                "session_closed"
            );
            sessions_.erase(found);
            return output;
        }

        // reset session
        if(const auto* reset = std::get_if<ResetEvent>(&event.payload)){
            session.checks.reset(reset->reason);
            ctx.emit(
                "trace",
                "engine",
                "reset",
                {{"reason", reset->reason}}
            );
        }

        // Send the current observation to the session's CheckManager for it
        // to call the detection handlers registered for its observation type.
        session.checks.dispatch(event, ctx);

        return output; // all Findings produced for the current observation
    }

}