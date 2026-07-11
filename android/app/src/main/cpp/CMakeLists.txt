cmake_minimum_required(VERSION 3.22.1)
project(osr_audio LANGUAGES C CXX)

include(FetchContent)

set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    opus
    GIT_REPOSITORY https://github.com/xiph/opus.git
    GIT_TAG v1.5.2
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(opus)

add_library(osr_audio SHARED osr_audio.cpp)
target_compile_features(osr_audio PRIVATE cxx_std_20)
target_compile_options(osr_audio PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(osr_audio PRIVATE aaudio log opus)
