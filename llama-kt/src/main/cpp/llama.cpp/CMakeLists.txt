cmake_minimum_required(VERSION 3.14...3.28)
project("llama.cpp" C CXX)
include(CheckIncludeFileCXX)

set(CMAKE_WARN_UNUSED_CLI YES)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
    set_property(CACHE CMAKE_BUILD_TYPE PROPERTY STRINGS "Debug" "Release" "MinSizeRel" "RelWithDebInfo")
endif()

message("CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}")

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake/")

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)

if (CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    set(LLAMA_STANDALONE ON)
    include(git-vars)
else()
    set(LLAMA_STANDALONE OFF)
endif()

set(BUILD_SHARED_LIBS_DEFAULT ON)
option(BUILD_SHARED_LIBS "build shared libraries" ${BUILD_SHARED_LIBS_DEFAULT})

# options
option(LLAMA_ALL_WARNINGS           "llama: enable all compiler warnings"                   ON)
option(LLAMA_ALL_WARNINGS_3RD_PARTY "llama: enable all compiler warnings in 3rd party libs" OFF)
option(LLAMA_FATAL_WARNINGS         "llama: enable -Werror flag"                            OFF)

option(LLAMA_SANITIZE_THREAD    "llama: enable thread sanitizer"    OFF)
option(LLAMA_SANITIZE_ADDRESS   "llama: enable address sanitizer"   OFF)
option(LLAMA_SANITIZE_UNDEFINED "llama: enable undefined sanitizer" OFF)

option(LLAMA_BUILD_COMMON "llama: build common utils library" ${LLAMA_STANDALONE})
option(LLAMA_OPENSSL      "llama: use openssl to support HTTPS" ON)
option(LLAMA_LLGUIDANCE   "llama-common: include LLGuidance library for structured output" OFF)

include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/build-info.cmake)
include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/common.cmake)

if (NOT DEFINED LLAMA_BUILD_NUMBER)
    set(LLAMA_BUILD_NUMBER ${BUILD_NUMBER})
endif()
if (NOT DEFINED LLAMA_BUILD_COMMIT)
    set(LLAMA_BUILD_COMMIT ${BUILD_COMMIT})
endif()
set(LLAMA_INSTALL_VERSION 0.0.${LLAMA_BUILD_NUMBER})

# override ggml options
set(GGML_ALL_WARNINGS   ${LLAMA_ALL_WARNINGS})
set(GGML_FATAL_WARNINGS ${LLAMA_FATAL_WARNINGS})

if (NOT DEFINED GGML_LLAMAFILE)
    set(GGML_LLAMAFILE_DEFAULT ON)
endif()

# transition helpers
function (llama_option_depr TYPE OLD)
    if (${OLD})
        set(NEW "${ARGV2}")
        if(NEW)
            message(${TYPE} "${OLD} is deprecated, use ${NEW} instead")
            set(${NEW} ON PARENT_SCOPE)
        else()
            message(${TYPE} "${OLD} is deprecated and will be ignored")
        endif()
    endif()
endfunction()

llama_option_depr(WARNING LLAMA_NATIVE GGML_NATIVE)

include("cmake/license.cmake")
license_add_file("llama.cpp" "LICENSE")

# ggml
if (NOT TARGET ggml)
    set(GGML_BUILD_NUMBER ${LLAMA_BUILD_NUMBER})
    set(GGML_BUILD_COMMIT ${LLAMA_BUILD_COMMIT})
    add_subdirectory(ggml)
endif()

# llama core
add_subdirectory(src)

# common utilities
if (LLAMA_BUILD_COMMON)
    add_subdirectory(common)
endif()

# Tool-Neuron engine
if (LLAMA_BUILD_COMMON)
    add_subdirectory(engine)
endif()

option(LLAMA_BUILD_TESTS "llama: build tests" ${LLAMA_STANDALONE})
if (LLAMA_BUILD_TESTS AND LLAMA_BUILD_COMMON)
    enable_testing()
    add_subdirectory(tests)
endif()

# licenses
file(GLOB EXTRA_LICENSES "${CMAKE_SOURCE_DIR}/licenses/LICENSE-*")
foreach(FILE_PATH ${EXTRA_LICENSES})
    get_filename_component(FILE_NAME "${FILE_PATH}" NAME)
    string(REGEX REPLACE "^LICENSE-" "" NAME "${FILE_NAME}")
    license_add_file("${NAME}" "${FILE_PATH}")
endforeach()

if (LLAMA_BUILD_COMMON)
    license_generate(common)
endif()

# install
include(GNUInstallDirs)
include(CMakePackageConfigHelpers)

set(LLAMA_INCLUDE_INSTALL_DIR ${CMAKE_INSTALL_INCLUDEDIR} CACHE PATH "Location of header files")
set(LLAMA_LIB_INSTALL_DIR     ${CMAKE_INSTALL_LIBDIR}     CACHE PATH "Location of library files")
set(LLAMA_BIN_INSTALL_DIR     ${CMAKE_INSTALL_BINDIR}     CACHE PATH "Location of binary files")

set(LLAMA_PUBLIC_HEADERS
    ${CMAKE_CURRENT_SOURCE_DIR}/include/llama.h
    ${CMAKE_CURRENT_SOURCE_DIR}/include/llama-cpp.h)

set_target_properties(llama PROPERTIES PUBLIC_HEADER "${LLAMA_PUBLIC_HEADERS}")

install(TARGETS llama LIBRARY PUBLIC_HEADER)

configure_package_config_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/cmake/llama-config.cmake.in
    ${CMAKE_CURRENT_BINARY_DIR}/llama-config.cmake
    INSTALL_DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/llama
    PATH_VARS LLAMA_INCLUDE_INSTALL_DIR LLAMA_LIB_INSTALL_DIR LLAMA_BIN_INSTALL_DIR)

write_basic_package_version_file(
    ${CMAKE_CURRENT_BINARY_DIR}/llama-version.cmake
    VERSION ${LLAMA_INSTALL_VERSION}
    COMPATIBILITY SameMajorVersion)

install(FILES ${CMAKE_CURRENT_BINARY_DIR}/llama-config.cmake
              ${CMAKE_CURRENT_BINARY_DIR}/llama-version.cmake
        DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/llama)

configure_file(cmake/llama.pc.in "${CMAKE_CURRENT_BINARY_DIR}/llama.pc" @ONLY)
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/llama.pc" DESTINATION ${CMAKE_INSTALL_LIBDIR}/pkgconfig)
