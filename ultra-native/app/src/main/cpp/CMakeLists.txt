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

# One shim per CPU variant from build-llama-android.sh, all with the same JNI symbols.
# LlmNative loads exactly one: the best the phone's /proc/cpuinfo supports.
# ultra_vlm.cpp (VisionNative: a vision-language model through llama.cpp's libmtmd) is in the
# same .so, so it shares the one llama/ggml. mtmd links before llama (it calls into it), and
# vendor-hash is mtmd's only other static dependency (build-llama-android.sh).
function(add_ultra_llm target libdir)
    add_library(${target} SHARED ultra_llm.cpp ultra_vlm.cpp)
    target_include_directories(${target} PRIVATE ${LLAMA_HOME}/include)
    target_link_directories(${target} PRIVATE ${LLAMA_HOME}/${libdir})
    target_link_libraries(${target}
            mtmd
            vendor-hash
            llama
            ggml-cpu
            ggml-base
            ggml
            log
            atomic
            )
    # 16KB-page devices (Android 15+); harmless on 4KB kernels
    target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384")
endfunction()

add_ultra_llm(ultra_llm lib)    # armv8-a: runs on any arm64 phone
foreach(variant dotprod i8mm)
    if(EXISTS "${LLAMA_HOME}/lib-${variant}/libggml-cpu.a" AND EXISTS "${LLAMA_HOME}/lib-${variant}/libmtmd.a")
        add_ultra_llm(ultra_llm_${variant} lib-${variant})
    else()
        message(WARNING "ultra_llm_${variant}: ${LLAMA_HOME}/lib-${variant} missing; "
                "phones that support it fall back to the slower armv8-a build. "
                "Re-run ondevice-brain/build-llama-android.sh")
    endif()
endforeach()

# --- whisper.cpp (ambient listening) ---------------------------------------
# Built into its OWN shared lib with ggml symbols hidden, so whisper's ggml and
# llama's ggml (ultra_llm) never resolve into each other. Build whisper.cpp for
# arm64 the same way as llama.cpp (ondevice-brain/build-whisper-android.sh):
#   $HOME/whisper-android/{lib,lib-dotprod,lib-i8mm,include}
# ($WHISPER_ANDROID_HOME overrides that path, for both the script and this file.)
# One shim per CPU variant, all with the same JNI symbols; WhisperNative loads
# exactly one: the best the phone's /proc/cpuinfo supports (CpuVariant).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    if(NOT "$ENV{WHISPER_ANDROID_HOME}" STREQUAL "")
        set(WHISPER_HOME "$ENV{WHISPER_ANDROID_HOME}")
    else()
        set(WHISPER_HOME "$ENV{HOME}/whisper-android")
    endif()
    function(add_ultra_whisper target libdir)
        add_library(${target} SHARED ultra_whisper.cpp)
        target_include_directories(${target} PRIVATE ${WHISPER_HOME}/include)
        target_link_directories(${target} PRIVATE ${WHISPER_HOME}/${libdir})
        target_link_libraries(${target}
                whisper ggml-cpu ggml-base ggml log atomic)
        # Hide ggml/whisper symbols so they don't collide with ultra_llm's ggml.
        target_link_options(${target} PRIVATE
                "-Wl,--exclude-libs,ALL"
                "-Wl,-z,max-page-size=16384"   # 16KB-page devices (Android 15+); harmless on 4KB
                "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/whisper_exports.map")
    endfunction()
    if(EXISTS "${WHISPER_HOME}/include/whisper.h")
        add_ultra_whisper(ultra_whisper lib)    # armv8-a: runs on any arm64 phone
        foreach(variant dotprod i8mm)
            if(EXISTS "${WHISPER_HOME}/lib-${variant}/libggml-cpu.a")
                add_ultra_whisper(ultra_whisper_${variant} lib-${variant})
            else()
                message(WARNING "ultra_whisper_${variant}: ${WHISPER_HOME}/lib-${variant} missing; "
                        "phones that support it fall back to the slower armv8-a build. "
                        "Re-run ondevice-brain/build-whisper-android.sh")
            endif()
        endforeach()
    else()
        message(STATUS "ultra_whisper: WHISPER_HOME not found; ambient listening disabled")
    endif()
endif()
