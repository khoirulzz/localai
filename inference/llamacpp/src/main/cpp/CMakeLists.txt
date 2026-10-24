cmake_minimum_required(VERSION 3.22.1)
project("localai_llama_jni" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Jika submodule llama.cpp tersedia di src/main/cpp/llama.cpp, aktifkan build asli:
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    add_subdirectory(llama.cpp)
    add_definitions(-DHAVE_LLAMA_CPP=1)
endif()

add_library(localai_llama_jni SHARED
    llama_jni.cpp
)

find_library(log-lib log)

if(TARGET llama)
    target_link_libraries(localai_llama_jni llama ggml ${log-lib})
else()
    target_link_libraries(localai_llama_jni ${log-lib})
endif()
