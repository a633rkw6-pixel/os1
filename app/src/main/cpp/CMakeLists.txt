cmake_minimum_required(VERSION 3.22.1)
project("vphone_native")

find_package(shadowhook REQUIRED CONFIG)

add_library(
    vphone_native
    SHARED
    VirtualMicClient.cpp
    vphone_io_hook.cpp
    vphone_audio_hook.cpp
    vphone_jni.cpp
)

target_include_directories(vphone_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(
    vphone_native
    shadowhook::shadowhook
    log
    android
)
