/**
 * finding.hpp contains the result/evidence produced when a check
 * (detector) in the detection engine finds something noteworthy
 */

/**
 * TODO:
 *   Add detector scores and calibrated probabilities once sufficient
 *   labeled data exists to validate them statistically.
 */

#pragma once
#include "event.hpp"
#include <string>
#include <utility>
#include <vector>

namespace ac{

    using Evidence = std::vector<std::pair<std::string, std::string>>;

    struct Finding{
        std::string level;             // "trace" or "suspicious"; not a probability.
        std::string check_id;
        std::string player_uuid;
        EventHeader event;
        std::string message;
        Evidence evidence;
    };

    std::string to_json(const Finding& finding);

}