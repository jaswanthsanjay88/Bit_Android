cmake_minimum_required(VERSION 3.22.1)
project("neuronpacket")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Production optimizations & reproducibility ──
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

# 16KB page size support for Android 15+
add_link_options(
    -Wl,--gc-sections
    -Wl,--icf=safe
    -Wl,-Bsymbolic
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384
    -Wl,--build-id=none
)

set(OPENSSL_ROOT_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
set(OPENSSL_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/third_party/openssl/include)

add_library(crypto SHARED IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${OPENSSL_ROOT_DIR}/libcrypto.so)

add_library(ssl SHARED IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${OPENSSL_ROOT_DIR}/libssl.so)

add_library(${CMAKE_PROJECT_NAME} SHARED
        neuronpacket.cpp
        third_party/lz4/lz4.c
        crypto/aes_gcm.cpp
        crypto/pbkdf2.cpp
        crypto/bcrypt.cpp
        crypto/sha256.cpp
        packet/neuron_header.cpp
        packet/acl_entry.cpp
        packet/packet_io.cpp
        utils/byte_buffer.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${CMAKE_SOURCE_DIR}
        ${CMAKE_SOURCE_DIR}/third_party/lz4
        ${OPENSSL_INCLUDE_DIR})

target_link_libraries(${CMAKE_PROJECT_NAME}
        crypto
        ssl
        android
        log)

