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
foreach(_tgt llama ggml ggml-base ggml-cpu ggml-vulkan)
    if(TARGET ${_tgt})
        set_target_properties(${_tgt} PROPERTIES VERSION "" SOVERSION "")
    endif()
endforeach()

add_library(${CMAKE_PROJECT_NAME} SHARED llama_jni.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
    PRIVATE
    llama)

if(NOT ANDROID)
    if(DEFINED ENV{JAVA_HOME})
        include_directories("$ENV{JAVA_HOME}/include" "$ENV{JAVA_HOME}/include/linux")
    endif()
    set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES BUILD_RPATH "$ORIGIN")
endif()
