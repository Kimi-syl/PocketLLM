cmake_minimum_required(VERSION 3.22.1)
project(pocketllm LANGUAGES C CXX)

set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

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
