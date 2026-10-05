cmake_minimum_required(VERSION 3.22.1)
project(sdcpp_jni LANGUAGES C CXX)

# Deliberately a separate build from libpocketllm.so: stable-diffusion.cpp
# vendors its own ggml and llama.cpp already exports one. Everything except the
# JNI entry points is compiled with hidden visibility so the two ggml copies
# cannot interpose against each other when both libraries are dlopen()ed.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/sd sd_build)

# Android has no libpthread: the pthread API lives in libc. Several third-party
# dependencies inside stable-diffusion.cpp still ask the linker for
# -lpthread/-lpthreads, which the NDK does not ship and ld.lld rejects with
# "unable to find library -lpthreads". Ship empty archives under both names so
# the request resolves; the real symbols come from libc.
foreach(_name pthread pthreads)
    file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/${_name}_stub.c "int pocketllm_${_name}_stub = 0;\n")
    add_library(${_name}_stub STATIC ${CMAKE_CURRENT_BINARY_DIR}/${_name}_stub.c)
    set_target_properties(${_name}_stub PROPERTIES OUTPUT_NAME ${_name})
    add_library(${_name} ALIAS ${_name}_stub)
endforeach()

set(_sd_target "")
foreach(_t stable-diffusion sd sd_lib SD_LIB)
    if(TARGET ${_t})
        set(_sd_target ${_t})
    endif()
endforeach()
if(_sd_target STREQUAL "")
    message(FATAL_ERROR "stable-diffusion.cpp library target not found")
endif()

add_library(sdcpp SHARED sd_jni.cpp)
target_link_libraries(sdcpp PRIVATE ${_sd_target})
# Also resolve a raw -lpthreads flag (not a CMake target) against the stubs.
target_link_directories(sdcpp PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
