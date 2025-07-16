cmake_minimum_required(VERSION 3.16)
cmake_policy(SET CMP0042 NEW)
cmake_policy(SET CMP0071 NEW)
cmake_policy(SET CMP0077 NEW)
cmake_policy(SET CMP0091 NEW)
if(${CMAKE_VERSION} VERSION_GREATER_EQUAL "3.24")
  cmake_policy(SET CMP0135 NEW)
endif()
set(CMAKE_POLICY_DEFAULT_CMP0042 NEW)
set(CMAKE_POLICY_DEFAULT_CMP0063 NEW)
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)

project(sleeve)
set(CMAKE_CXX_STANDARD 20)

if(NOT DEFINED CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE "Debug")
endif()

include(FetchContent)
find_package(PkgConfig)
include(cmake/ffmpeg.cmake)

pkg_check_modules(PROTOBUF REQUIRED protobuf)
pkg_check_modules(ZLIB REQUIRED zlib)
pkg_check_modules(XCB REQUIRED xcb)
pkg_check_modules(XCB_SHM REQUIRED xcb-shm)
pkg_check_modules(XCB_XFIXES REQUIRED xcb-xfixes)
pkg_check_modules(XCB_SHAPE REQUIRED xcb-shape)
pkg_check_modules(OPENSSL REQUIRED openssl)
find_package(Boost)

macro(apply_protoc OUTPUT_SOURCES)
  
  foreach(PROTO_FILE ${ARGN})
    get_filename_component(PROTO_ABSOLUTE "${PROTO_FILE}" ABSOLUTE)
    get_filename_component(PROTO_NAME "${PROTO_FILE}" NAME_WE)
    get_filename_component(PROTO_DIRECTORY "${PROTO_ABSOLUTE}" DIRECTORY)
    set(apply_protoc_grpc_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/${PROTO_NAME}.pb.h"
                                    "${CMAKE_CURRENT_BINARY_DIR}/${PROTO_NAME}.pb.cc")
    add_custom_command(
          OUTPUT ${apply_protoc_grpc_GENERATED}
          COMMAND protoc
          --cpp_out "${CMAKE_CURRENT_BINARY_DIR}"
          -I "${PROTO_DIRECTORY}"
          ${PROTO_ABSOLUTE}
          DEPENDS "${PROTO_ABSOLUTE}")
    if("${CMAKE_CXX_COMPILER_ID}" MATCHES "MSVC")
      set_source_files_properties(
        ${apply_protoc_grpc_GENERATED}
        PROPERTIES
        COMPILE_FLAGS /wd4267)
    else()
      set_source_files_properties(
        ${apply_protoc_grpc_GENERATED}
        PROPERTIES
        COMPILE_FLAGS -Wno-everything)
    endif()
    list(APPEND ${OUTPUT_SOURCES} ${apply_protoc_grpc_GENERATED})
  endforeach()

endmacro()

set(PROTO_SOURCES
  proto/thalamus.proto)
apply_protoc(PROTO_GENERATED_SOURCES ${PROTO_SOURCES})

target_link_libraries(ffmpeg INTERFACE ${XCB_LIBRARIES} ${XCB_SHM_LIBRARIES} ${XCB_XFIXES_LIBRARIES} ${XCB_SHAPE_LIBRARIES})

add_executable(server src/main.cpp ${PROTO_GENERATED_SOURCES})
target_link_libraries(server PRIVATE ffmpeg ${PROTOBUF_LIBRARIES} ${ZLIB_LIBRARIES} ${BOOST_LIBRARIES} ${OPENSSL_LIBRARIES})
target_include_directories(server PRIVATE "${PROTOBUF_INCLUDE_DIRS}" "${CMAKE_BINARY_DIR}" ${BOOST_INCLUDE_DIRS})
#target_compile_options(server PRIVATE -fsanitize=address)
#target_link_options(server PRIVATE -fsanitize=address)

