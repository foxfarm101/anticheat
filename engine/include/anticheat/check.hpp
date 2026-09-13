/**
 * check.hpp defines the shared structures for individual detection checks and
 * dispatches observations (from the Java adapter) to the checks that handle them.
 * 
 * Check: One detection method, such as FastBreak.
 * Handler: A function belonging to a check that processes a particular type of observation.
 * CheckManager: Routes incoming observations to the checks that registered handlers for its type and calls those handlers.
 */

#pragma once
#include "finding.hpp"
#include <functional>
#include <memory>
#include <stdexcept>
#include <string_view>
#include <typeindex>
#include <unordered_map>
#include <vector>

namespace ac{

    // Shared state for one player session, accessible to all checks.
    struct PlayerState{
        SessionId session{};
        SessionStart identity;
        std::uint64_t last_ordinal{};
        // Shared trackers belong here when multiple checks need them
    };

    // Provides a check (detector) with the current player/event context and finding output
    struct CheckContext{
        const PlayerState& player;
        const EventHeader& event;
        bool trace_enabled;
        std::vector<Finding>& output;

        // Report a finding for the observation currently being processed
        void emit(std::string level, std::string id, std::string message, Evidence evidence = {}){
            if(level == "trace" && !trace_enabled){
                return;
            }

            // Construct and append a new finding to the output collection
            output.push_back({
                std::move(level),
                std::move(id),
                player.identity.player_uuid,
                event,
                std::move(message),
                std::move(evidence)
            });
        }
    };

    class CheckManager;

    // Interface that is implemented by every detection check
    class Check{
    public:
        virtual ~Check() = default;
        
        // unique ID for this check
        virtual std::string_view id() const noexcept = 0;
        
        // register handlers for the observation types this check uses
        virtual void registerHandlers(CheckManager& manager) = 0;
        
        // clear the detection hbistory that should not longer influence future observations
        virtual void reset(std::string_view reason) = 0;
    };

    // Owns checks and dispatches observations to their registered handlers.
    // Main-thread only; all handlers must be registered before dispatch.
    class CheckManager{
        using Handler = std::function<void(const Event&, CheckContext&)>;
        std::vector<std::unique_ptr<Check>> checks_;
        std::unordered_map<std::type_index, std::vector<Handler>> handlers_;
        bool sealed_{};
    public:
        // register a callback for observations with payload type T
        template<class T, class F>
        void on(F&& callback){
            if(sealed_){
                throw std::logic_error("Register handlers before dispatch");
            }

            auto handler = [fn = std::forward<F>(callback)](
                const Event& event,
                CheckContext& ctx
            ){
                fn(std::get<T>(event.payload), ctx);
            };

            handlers_[std::type_index(typeid(T))].emplace_back(
                std::move(handler)
            );
        }

        // register a check and its observation handlers with this manager
        void add(std::unique_ptr<Check> check){
            if(sealed_ || !check){
                throw std::logic_error("Invalid check registration");
            }
            for(const auto& existing : checks_){
                if(existing->id() == check->id()){
                    throw std::logic_error("Duplicate check ID");
                }
            }
            checks_.push_back(std::move(check));
            checks_.back()->registerHandlers(*this);
        }

        // send an observation to every handler registered for its payload type
        void dispatch(const Event& event, CheckContext& ctx){
            sealed_ = true;
            std::visit([&](const auto& payload){
                auto found = handlers_.find(std::type_index(typeid(payload)));
                if(found != handlers_.end()){
                    for(auto& fn : found->second){ fn(event, ctx); }
                }
            }, event.payload);
        }

        // reset the internal history of every registered check
        void reset(std::string_view reason){
            for(auto& check : checks_){ check->reset(reason); }
        }
    };

    // creates a fresh check instance for a player session
    using CheckFactory = std::function<std::unique_ptr<Check>()>;

}