cmake_minimum_required(VERSION 3.22.1)
project(pocketllm LANGUAGES C CXX)

set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(${CMAKE_PROJECT_NAME} SHARED llama_jni.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
    PRIVATE
    llama)
