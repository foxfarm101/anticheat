/**
 * builtins.hpp declares the function used to create/register detection checks
 */

#pragma once
#include "check.hpp"
#include <string_view>

namespace ac{
    struct EngineSetup{
        bool trace;
        std::vector<CheckFactory> factories;
    };

    // The composition root knows concrete checks; Engine and CheckManager do not.
    EngineSetup builtin_checks(std::string_view configuration);
}