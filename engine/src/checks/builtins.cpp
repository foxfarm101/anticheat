/**
 * builtins.cpp constructs and configures the detection checks
 * 
 * “Built-in checks” simply mean detectors that ship as part of the anticheat, such as FastBreakCheck or FlyCheck.
 */

#include "anticheat/builtins.hpp"
#include "fastbreak.hpp"
#include <cmath>
#include <locale>
#include <map>
#include <sstream>
#include <stdexcept>

namespace ac{

    namespace{

        std::string trim(std::string s){
            auto first = s.find_first_not_of(" \t\r\n");

            if(first == std::string::npos)
                return {};

            auto last = s.find_last_not_of(" \t\r\n");
            return s.substr(first, last - first + 1);
        }

    }

    EngineSetup builtin_checks(std::string_view configuration){
        if(configuration.size() > 16384)
            throw std::invalid_argument("Configuration too large");

        std::istringstream input{std::string(configuration)};
        std::map<std::string, std::string> values;
        std::string line;

        // Parse the configuration into unconsumed key/value settings.
        while(std::getline(input, line)){
            line = trim(line);

            if(line.empty() || line.front() == '#')
                continue;

            auto eq = line.find('=');

            if(eq == std::string::npos)
                throw std::invalid_argument("Expected key=value");

            auto key = trim(line.substr(0, eq));
            auto value = trim(line.substr(eq + 1));

            if(!values.emplace(key, value).second)
                throw std::invalid_argument("Duplicate setting: " + key);
        }

        // Read and consume a boolean setting.
        auto boolean = [&](const std::string& key, bool fallback){
            auto it = values.find(key);

            if(it == values.end())
                return fallback;

            auto value = it->second;
            values.erase(it);

            if(value == "true")
                return true;

            if(value == "false")
                return false;

            throw std::invalid_argument("Expected true/false: " + key);
        };

        // Read, validate, and consume a numeric setting.
        auto number = [&](const std::string& key, double fallback){
            auto it = values.find(key);

            if(it == values.end())
                return fallback;

            std::istringstream stream(it->second);
            stream.imbue(std::locale::classic());

            double value{};
            stream >> value;

            if(!stream || !std::isfinite(value))
                throw std::invalid_argument("Invalid number: " + key);

            stream >> std::ws;

            if(!stream.eof())
                throw std::invalid_argument("Trailing number data: " + key);

            values.erase(it);
            return value;
        };

        EngineSetup setup{
            boolean("trace", true),
            {}
        };

        bool fastbreak_enabled = boolean("fastbreak.enabled", true);

        FastBreakSettings settings;
        settings.maximum_ratio = number(
            "fastbreak.maximum_ratio",
            settings.maximum_ratio
        );
        settings.grace_ms = number(
            "fastbreak.grace_ms",
            settings.grace_ms
        );
        settings.minimum_expected_ms = number(
            "fastbreak.minimum_expected_ms",
            settings.minimum_expected_ms
        );
        settings.maximum_queue_ms = number(
            "fastbreak.maximum_queue_ms",
            settings.maximum_queue_ms
        );
        settings.sample_window_ms = number(
            "fastbreak.sample_window_ms",
            settings.sample_window_ms
        );

        double alert_after = number(
            "fastbreak.alert_after",
            settings.alert_after
        );

        if(alert_after < 1 ||
           alert_after > 10000 ||
           std::floor(alert_after) != alert_after)
        {
            throw std::invalid_argument("Invalid alert_after");
        }

        settings.alert_after = static_cast<std::uint32_t>(alert_after);

        // Validate the complete FastBreak configuration immediately.
        (void)FastBreakCheck(settings);

        // Any remaining key was never recognized or consumed.
        if(!values.empty())
            throw std::invalid_argument(
                "Unknown setting: " + values.begin()->first
            );

        // Register the FastBreak factory only when the check is enabled.
        if(fastbreak_enabled){
            setup.factories.push_back([settings]{
                return std::make_unique<FastBreakCheck>(settings);
            });
        }

        return setup;
    }

}