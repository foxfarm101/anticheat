cmake_minimum_required(VERSION 3.24)
project(anticheat VERSION 0.2.0 LANGUAGES CXX)
option(AC_BUILD_JNI "Build the Java/native bridge and its smoke test" ON)
option(AC_SANITIZE "Address/undefined sanitizers for native-only tests" OFF)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    add_compile_options(/W4 /WX /permissive-)
else()
    add_compile_options(-Wall -Wextra -Wpedantic -Werror)
endif()
if(AC_SANITIZE)
    if(MSVC OR AC_BUILD_JNI)
        message(FATAL_ERROR "Use AC_SANITIZE with GCC/Clang and AC_BUILD_JNI=OFF")
    endif()
    add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
    add_link_options(-fsanitize=address,undefined)
endif()

add_library(anticheat_engine STATIC
    engine/src/engine.cpp
    engine/src/finding.cpp
    engine/src/checks/fastbreak.cpp
    engine/src/checks/builtins.cpp)
target_include_directories(anticheat_engine PUBLIC engine/include)
add_library(anticheat_wire STATIC bridge/wire.cpp)
target_include_directories(anticheat_wire PUBLIC bridge)
target_link_libraries(anticheat_wire PUBLIC anticheat_engine)

include(CTest)
if(BUILD_TESTING)
    add_executable(engine_tests tests/engine_tests.cpp)
    target_include_directories(engine_tests PRIVATE engine/src/checks)
    target_link_libraries(engine_tests PRIVATE anticheat_wire)
    add_test(NAME engine_tests COMMAND engine_tests)
endif()

if(AC_BUILD_JNI)
    if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
        message(FATAL_ERROR "This JNI starter targets x64; configure an x64 compiler")
    endif()
    find_package(Java 11 REQUIRED COMPONENTS Development Runtime)
    if(NOT DEFINED ENV{JAVA_HOME})
        get_filename_component(java_real "${Java_JAVA_EXECUTABLE}" REALPATH)
        get_filename_component(java_bin "${java_real}" DIRECTORY)
        get_filename_component(JAVA_HOME "${java_bin}" DIRECTORY)
    endif()
    find_package(JNI REQUIRED COMPONENTS JVM)
    set(java_root "${CMAKE_CURRENT_SOURCE_DIR}/plugin/src/main/java/dev/fox/anticheat")
    set(java_sources
        "${java_root}/bridge/NativeBridge.java"
        "${java_root}/bridge/EventWriter.java"
        "${java_root}/event/DigEvent.java"
        "${java_root}/event/MiningContext.java"
        "${java_root}/packet/PacketInfo.java"
        "${java_root}/packet/PacketHandlers.java"
        "${java_root}/observation/ObservationModule.java"
        "${CMAKE_CURRENT_SOURCE_DIR}/tests/PacketHandlersTest.java"
        "${CMAKE_CURRENT_SOURCE_DIR}/tests/NativeSmokeTest.java")
    set(jni_header "${CMAKE_CURRENT_BINARY_DIR}/generated/dev_fox_anticheat_bridge_NativeBridge.h")
    add_custom_command(OUTPUT "${jni_header}"
        COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_BINARY_DIR}/generated" "${CMAKE_CURRENT_BINARY_DIR}/java"
        COMMAND "${Java_JAVAC_EXECUTABLE}" --release 8 -encoding UTF-8
            -h "${CMAKE_CURRENT_BINARY_DIR}/generated" -d "${CMAKE_CURRENT_BINARY_DIR}/java" ${java_sources}
        DEPENDS ${java_sources}
        VERBATIM)
    add_custom_target(bridge_java DEPENDS "${jni_header}")
    add_library(anticheat_native SHARED bridge/jni.cpp "${jni_header}")
    add_dependencies(anticheat_native bridge_java)
    target_include_directories(anticheat_native PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/generated" ${JNI_INCLUDE_DIRS})
    target_link_libraries(anticheat_native PRIVATE anticheat_wire)
    # A library loaded by an existing JVM needs the JNI headers, not a link to libjvm.
    if(BUILD_TESTING)
        add_test(NAME packet_handlers COMMAND "${Java_JAVA_EXECUTABLE}"
            -cp "${CMAKE_CURRENT_BINARY_DIR}/java" dev.fox.anticheat.packet.PacketHandlersTest)
        add_test(NAME jni_smoke COMMAND "${Java_JAVA_EXECUTABLE}" -Xcheck:jni
            -cp "${CMAKE_CURRENT_BINARY_DIR}/java" dev.fox.anticheat.bridge.NativeSmokeTest
            "$<TARGET_FILE:anticheat_native>")
    endif()
endif()
