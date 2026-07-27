cmake_minimum_required(VERSION 3.22.1)
project("ums")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_compile_options(
    -finput-charset=UTF-8
    -fexec-charset=UTF-8
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.
    -ffile-prefix-map=${CMAKE_BINARY_DIR}=.
    -fmacro-prefix-map=${CMAKE_SOURCE_DIR}=.
    -fmacro-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.
    -fmacro-prefix-map=${CMAKE_BINARY_DIR}=.
    -fdebug-prefix-map=${CMAKE_SOURCE_DIR}=.
    -fdebug-prefix-map=${CMAKE_BINARY_DIR}=.

    -ffunction-sections
    -fdata-sections
)
add_compile_options($<$<NOT:$<CONFIG:Debug>>:-flto>)
add_compile_options($<$<NOT:$<CONFIG:Debug>>:-DNDEBUG>)

add_link_options(
    -Wl,--gc-sections
    -Wl,--icf=safe
    -Wl,-Bsymbolic
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384
    -Wl,--build-id=none
)
add_link_options($<$<NOT:$<CONFIG:Debug>>:-flto>)

# BoringSSL — same pinned version as system_encryptor
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
include(FetchContent)
FetchContent_Declare(
    boringssl
    URL "https://github.com/google/boringssl/archive/617634bc015344093f5ea0b0a5b653c924cfa20d.zip"
)
FetchContent_MakeAvailable(boringssl)

# LZ4 — fast compression for per-record storage
FetchContent_Declare(
    lz4
    URL "https://github.com/lz4/lz4/archive/refs/tags/v1.10.0.zip"
)
FetchContent_Populate(lz4)

# Sister module source directories (4 levels up from ums/src/main/cpp/ to project root)
set(SYS_ENC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../system_encryptor/src/main/cpp")
set(FILE_OPS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../file_ops/src/main/cpp")

add_library(${CMAKE_PROJECT_NAME} SHARED
    ums.cpp
    wire_format.cpp
    collection.cpp
    index.cpp
    wal.cpp
    manifest.cpp
    ${SYS_ENC_DIR}/crypto_engine.cpp
    ${SYS_ENC_DIR}/memory_guard.cpp
    ${FILE_OPS_DIR}/path_guard.cpp
    ${FILE_OPS_DIR}/io_engine.cpp
    ${lz4_SOURCE_DIR}/lib/lz4.c
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${boringssl_SOURCE_DIR}/include
    ${SYS_ENC_DIR}
    ${FILE_OPS_DIR}
    ${lz4_SOURCE_DIR}/lib
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    ssl
    crypto
)

set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES
    CXX_VISIBILITY_PRESET hidden
)
