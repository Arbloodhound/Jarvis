cmake_minimum_required(VERSION 3.22.1)
project(ultra_llm CXX)

set(CMAKE_CXX_STANDARD 17)

# llama.cpp static libs + headers, built once for arm64 (see DEVLOG session 15).
# Nothing is built for any other ABI: the x86_64 emulator build (-Pbench, for the
# AndroidWorld benchmark) ships without the on-device model rather than failing to link.
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "ultra_llm: skipping ABI ${ANDROID_ABI} (llama.cpp is built for arm64 only)")
    return()
endif()

set(LLAMA_HOME "$ENV{HOME}/llama-android")

add_library(ultra_llm SHARED ultra_llm.cpp)

target_include_directories(ultra_llm PRIVATE ${LLAMA_HOME}/include)

target_link_directories(ultra_llm PRIVATE ${LLAMA_HOME}/lib)

target_link_libraries(ultra_llm
        llama
        ggml-cpu
        ggml-base
        ggml
        log
        atomic
        )

# --- whisper.cpp (ambient listening) ---------------------------------------
# Built into its OWN shared lib with ggml symbols hidden, so whisper's ggml and
# llama's ggml (ultra_llm) never resolve into each other. Build whisper.cpp for
# arm64 the same way as llama.cpp and drop its static libs beside llama's:
#   $HOME/whisper-android/{lib,include}
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(WHISPER_HOME "$ENV{HOME}/whisper-android")
    if(EXISTS "${WHISPER_HOME}/include/whisper.h")
        add_library(ultra_whisper SHARED ultra_whisper.cpp)
        target_include_directories(ultra_whisper PRIVATE ${WHISPER_HOME}/include)
        target_link_directories(ultra_whisper PRIVATE ${WHISPER_HOME}/lib)
        target_link_libraries(ultra_whisper
                whisper ggml-cpu ggml-base ggml log atomic)
        # Hide ggml/whisper symbols so they don't collide with ultra_llm's ggml.
        target_link_options(ultra_whisper PRIVATE
                "-Wl,--exclude-libs,ALL"
                "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/whisper_exports.map")
    else()
        message(STATUS "ultra_whisper: WHISPER_HOME not found; ambient listening disabled")
    endif()
endif()
