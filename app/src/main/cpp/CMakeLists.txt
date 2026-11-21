cmake_minimum_required(VERSION 3.22)
project(aismo_llm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Play requires 16 KB memory page size support for native code in apps targeting API 35 and up.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

# llama.cpp is fetched by the Gradle task fetchLlamaCpp (a pinned release) and never committed.
if(NOT DEFINED LLAMA_CPP_DIR OR NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_CPP_DIR does not point at the llama.cpp source (run the fetchLlamaCpp Gradle task)")
endif()

# The CPU code is built in several variants (for different instruction sets) and the best one for the phone is
# loaded at run time, so one build runs on older and newer chips alike.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86")
endif()
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)   # would download another library while building

add_subdirectory(${LLAMA_CPP_DIR} build-llama)

add_library(aismo_llm SHARED aismo_llm.cpp)
target_include_directories(aismo_llm PRIVATE ${LLAMA_CPP_DIR}/include ${LLAMA_CPP_DIR}/ggml/include)
target_link_libraries(aismo_llm PRIVATE llama android log)
