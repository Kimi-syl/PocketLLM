cmake_minimum_required(VERSION 3.22.1)
project(pocketllm LANGUAGES C CXX)

set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# The Vulkan/OpenCL shims are built for arm64 only (vkshim is loaded through the
# same libvkshim.so that ships for aarch64). On x86_64 - which is an
# emulator/desktop target where the sandbox stack is unavailable anyway - keep
# the CPU path only rather than dragging in a shim we cannot build.
if(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
else()
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(llama.cpp)

# Override versioned SONAMEs from llama.cpp — Android only loads *.so files
foreach(_tgt llama ggml ggml-base ggml-cpu ggml-vulkan ggml-opencl)
    if(TARGET ${_tgt})
        set_target_properties(${_tgt} PROPERTIES VERSION "" SOVERSION "")
    endif()
endforeach()

add_library(${CMAKE_PROJECT_NAME} SHARED llama_jni.cpp)

# vulkan_shim.c compiles into the standalone libvkshim.so (its source lives
# here for reference; the shim is built by build_shim.sh and vendored into
# jniLibs). llama_jni.cpp imports its entry points via DT_NEEDED.

target_link_libraries(${CMAKE_PROJECT_NAME}
    PRIVATE
    llama
    ${Vulkan_LIBRARY})

if(NOT ANDROID)
    if(DEFINED ENV{JAVA_HOME})
        include_directories("$ENV{JAVA_HOME}/include" "$ENV{JAVA_HOME}/include/linux")
    endif()
    set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES BUILD_RPATH "$ORIGIN")
endif()
