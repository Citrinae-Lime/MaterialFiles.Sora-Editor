cmake_minimum_required(VERSION 3.6)

project(MaterialFiles C)

if (CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    # Enable LTO optimization for arm64-v8a
    add_compile_options(
        -fdata-sections
        -ffunction-sections
        -flto
    )
    add_link_options(
        LINKER:--gc-sections
    )
endif()

add_library(hiddenapi SHARED
        src/main/jni/hiddenapi.c)
target_compile_options(hiddenapi
        PRIVATE
        -Wall
        -Werror)

find_library(LOG_LIBRARY log)
add_library(syscall SHARED
        src/main/jni/syscall.c)
target_compile_options(syscall
        PRIVATE
        -Wall
        -Werror)
target_link_libraries(syscall
        PRIVATE
        ${LOG_LIBRARY})
