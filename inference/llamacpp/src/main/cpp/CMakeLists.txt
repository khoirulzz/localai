cmake_minimum_required(VERSION 3.22.1)
project("localai_llama_jni" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Jika source llama.cpp ada di src/main/cpp/llama.cpp, aktifkan build asli.
# (CI meng-clone tag yang di-pin; lokal: git clone ke folder ini atau jadikan submodule.)
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    # Pustaka multimodal (gambar) tanpa membangun seluruh tools
    set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
    set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
    set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
    # Hanya backend CPU (perangkat target tidak punya GPU compute yang andal)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    # Kernel dot-product (ARMv8.2) mempercepat matmul kuantisasi Q4 secara signifikan.
    # Ponsel arm64 sejak ~2019 (Cortex-A55/A76 ke atas) mendukungnya.
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
    endif()

    add_subdirectory(llama.cpp ${CMAKE_BINARY_DIR}/llama.cpp)
    add_definitions(-DHAVE_LLAMA_CPP=1)
    if(TARGET mtmd)
        add_definitions(-DHAVE_MTMD=1)
    endif()
endif()

add_library(localai_llama_jni SHARED
    llama_jni.cpp
)

find_library(log-lib log)

if(TARGET llama)
    target_include_directories(localai_llama_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include)
    target_link_libraries(localai_llama_jni llama ggml ${log-lib})
    if(TARGET mtmd)
        target_include_directories(localai_llama_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/tools/mtmd)
        target_link_libraries(localai_llama_jni mtmd)
    endif()
else()
    target_link_libraries(localai_llama_jni ${log-lib})
endif()
