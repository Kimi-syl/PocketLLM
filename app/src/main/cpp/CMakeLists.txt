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

set(_pocketllm_libs llama)
if(NOT ANDROID_ABI STREQUAL "x86_64")
    # libvkshim.so is an arm64 binary; linking it on x86_64 fails with
    # "libvkshim.so is incompatible with elf_x86_64".
    list(APPEND _pocketllm_libs ${Vulkan_LIBRARY})
else()
    # x86_64 has no prebuilt shim to link, but llama_jni.cpp still calls into
    # it (vulkan_shim_gpu_usable, vulkan_shim_driver_broken, ...). Compile the
    # shim sources directly so those resolve; GGML_VULKAN is off here so the
    # symbols are only used for the probe verdicts.
    target_sources(${CMAKE_PROJECT_NAME} PRIVATE vulkan_shim.c opencl_shim.c)
endif()

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE ${_pocketllm_libs})

if(NOT ANDROID)
    if(DEFINED ENV{JAVA_HOME})
        include_directories("$ENV{JAVA_HOME}/include" "$ENV{JAVA_HOME}/include/linux")
    endif()
    set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES BUILD_RPATH "$ORIGIN")
endif()
