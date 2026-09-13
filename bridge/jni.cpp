#include <jni.h>
#include "dev_fox_anticheat_bridge_NativeBridge.h"
#include "anticheat/builtins.hpp"
#include "anticheat/engine.hpp"
#include "wire.hpp"
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <unordered_map>

namespace{
struct Entry{
    std::thread::id owner;
    ac::Engine engine;
    explicit Entry(ac::EngineSetup setup)
        : owner(std::this_thread::get_id()), engine(setup.trace, std::move(setup.factories)){}
};
std::mutex registry_mutex;
std::unordered_map<jlong, std::unique_ptr<Entry>> registry;
jlong next_handle = 1; // IDs, never exported pointers; stale IDs cannot dereference freed memory.
void raise(JNIEnv* env, const char* kind, const char* message){
    if(env->ExceptionCheck()){ return; }
    jclass klass = env->FindClass(kind);
    if(klass){ env->ThrowNew(klass, message); env->DeleteLocalRef(klass); }
}
Entry& get(jlong handle){
    auto it = registry.find(handle);
    if(it == registry.end()){ throw std::logic_error("Closed or invalid engine handle"); }
    if(it->second->owner != std::this_thread::get_id()){
        throw std::logic_error("Call the engine on its owning server thread");
    }
    return *it->second;
}
struct Utf{
    JNIEnv* env; jstring source; const char* chars;
    Utf(JNIEnv* e, jstring s) : env(e), source(s), chars(e->GetStringUTFChars(s, nullptr)){}
    ~Utf(){ if(chars){ env->ReleaseStringUTFChars(source, chars); } }
};
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*){ return JNI_VERSION_1_8; }

extern "C" JNIEXPORT jlong JNICALL
Java_dev_fox_anticheat_bridge_NativeBridge_nCreate(JNIEnv* env, jclass, jstring configuration){
    try{
        if(!configuration || env->GetStringLength(configuration) > 16384){
            throw std::invalid_argument("Missing or oversized configuration");
        }
        Utf config(env, configuration);
        if(!config.chars){ return 0; }
        auto entry = std::make_unique<Entry>(ac::builtin_checks(config.chars));
        std::lock_guard<std::mutex> lock(registry_mutex);
        if(next_handle == std::numeric_limits<jlong>::max()){
            throw std::runtime_error("Engine ID space exhausted");
        }
        auto handle = next_handle++;
        registry.emplace(handle, std::move(entry));
        return handle;
    }catch(const std::exception& e){ raise(env, "java/lang/IllegalStateException", e.what()); }
    catch(...){ raise(env, "java/lang/IllegalStateException", "Unknown native create error"); }
    return 0;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_fox_anticheat_bridge_NativeBridge_nDestroy(JNIEnv* env, jclass, jlong handle){
    try{
        std::lock_guard<std::mutex> lock(registry_mutex);
        (void)get(handle); registry.erase(handle);
    }catch(const std::exception& e){ raise(env, "java/lang/IllegalStateException", e.what()); }
    catch(...){ raise(env, "java/lang/IllegalStateException", "Unknown native destroy error"); }
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_fox_anticheat_bridge_NativeBridge_nSubmit(JNIEnv* env, jclass, jlong handle, jobject buffer, jint size){
    try{
        if(!buffer || size < 0 || size > 8192){ throw std::invalid_argument("Invalid event size"); }
        auto capacity = env->GetDirectBufferCapacity(buffer);
        auto bytes = static_cast<const std::uint8_t*>(env->GetDirectBufferAddress(buffer));
        if(!bytes || capacity < size){ throw std::invalid_argument("Expected a sufficiently large direct buffer"); }
        auto event = ac::decode_event(bytes, static_cast<std::size_t>(size));
        std::lock_guard<std::mutex> lock(registry_mutex);
        auto findings = get(handle).engine.process(event);
        if(findings.empty()){ return nullptr; }
        jclass string_class = env->FindClass("java/lang/String");
        if(!string_class){ return nullptr; }
        auto output = env->NewObjectArray(static_cast<jsize>(findings.size()), string_class, nullptr);
        env->DeleteLocalRef(string_class);
        if(!output){ return nullptr; }
        for(std::size_t i = 0; i < findings.size(); ++i){
            // Serializer returns ASCII-only JSON, also valid modified UTF-8 for JNI.
            auto text = ac::to_json(findings[i]);
            auto string = env->NewStringUTF(text.c_str());
            if(!string){ return nullptr; }
            env->SetObjectArrayElement(output, static_cast<jsize>(i), string);
            env->DeleteLocalRef(string);
            if(env->ExceptionCheck()){ return nullptr; }
        }
        return output;
    }catch(const std::exception& e){ raise(env, "java/lang/IllegalStateException", e.what()); }
    catch(...){ raise(env, "java/lang/IllegalStateException", "Unknown native submit error"); }
    return nullptr;
}
