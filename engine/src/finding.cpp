/**
 * finding.cpp implements serialization of detection Findings for transfer
 * from the C++ detection engine to the Java plugin.
 */

#include "anticheat/finding.hpp"
#include <locale>
#include <sstream>

namespace ac{

    namespace{
        // Convert a C str into a quoted JSON str before Finding is serialized and sent to Java
        std::string quote(const std::string& text){
            static const char hex[] = "0123456789abcdef";
            std::string out = "\"";
            for(unsigned char c : text){
                if(c == '"' || c == '\\'){
                    out += '\\';
                    out += static_cast<char>(c);
                }
                else if(c < 32 || c >= 127){
                    out += "\\u00";
                    out += hex[c >> 4];
                    out += hex[c & 15];
                }else{
                    out += static_cast<char>(c);
                }
            }
            out += '"';
            return out;
        }
    }

    // Convert the Finding into JSON so the JNI bridge can return it to Java
    std::string to_json(const Finding& f){
        std::ostringstream out;
        out.imbue(std::locale::classic());
        // IDs/timestamps are strings so future JS dashboards will not round uint64 values, which JS can't represent exactly.
        out << "{\"level\":" << quote(f.level) << ",\"check\":" << quote(f.check_id)
            << ",\"player\":" << quote(f.player_uuid)
            << ",\"session\":" << quote(std::to_string(f.event.session))
            << ",\"event\":" << quote(std::to_string(f.event.ordinal))
            << ",\"observed_ns\":" << quote(std::to_string(f.event.observed_ns))
            << ",\"epoch_ms\":" << quote(std::to_string(f.event.epoch_ms))
            << ",\"server_tick\":" << quote(std::to_string(f.event.server_tick))
            << ",\"message\":" << quote(f.message) << ",\"evidence\":{";
        bool first = true;
        for(const auto& field : f.evidence){
            if(!first){ out << ','; } first = false;
            out << quote(field.first) << ':' << quote(field.second);
        }
        out << "}}";
        return out.str();
    }

}