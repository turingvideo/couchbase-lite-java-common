#
# CMakeLists.txt
#
# Copyright (c) 2017 Couchbase, Inc All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http:#www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

cmake_minimum_required(VERSION 3.4.1)

project (LiteCoreJNI)

set(LITE_CORE_ROOT ${PROJECT_SOURCE_DIR}/../../core)

find_package(Java COMPONENTS Development)
include (UseJava)

# This script is only for Android
if (NOT ${CMAKE_SYSTEM_NAME} STREQUAL "Android")
    MESSAGE( FATAL_ERROR "!!!UNEXPECTED SYSTEM (expecting 'Android': "     ${CMAKE_SYSTEM_NAME} )
endif()

MESSAGE( STATUS "JAVA FOUND: "            ${Java_FOUND} )
MESSAGE( STATUS "JAVA INCLUDE DIRS: "     ${Java_INCLUDE_DIRS} )
MESSAGE( STATUS "JAVA INCLUDE PATH: "     ${JAVA_INCLUDE_PATH} )
MESSAGE( STATUS "JAVA INCLUDE PATH2: "    ${JAVA_INCLUDE_PATH2} )
MESSAGE( STATUS "JAVA JAVAH EXECUTABLE: " ${Java_JAVAH_EXECUTABLE} )
MESSAGE( STATUS "CORE ROOT: " ${LITE_CORE_ROOT} )
MESSAGE( STATUS "OUTPUT DIR: " ${CMAKE_LIBRARY_OUTPUT_DIRECTORY} )

set(COMPILE_FLAGS   "${COMPILE_FLAGS}   -Wall -Werror")
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)


#
# Generate JNI header files
#
set(JNI_OUTPUT_DIR ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/include)
add_custom_target(JNI_DIR ALL COMMAND ${CMAKE_COMMAND} -E make_directory ${JNI_OUTPUT_DIR})

set(JNI_CLASSPATH  ${PROJECT_SOURCE_DIR}/main/java/)
set(JNI_CLASSES
    com.couchbase.lite.internal.core.C4
    com.couchbase.lite.internal.core.C4Base
    com.couchbase.lite.internal.core.C4BlobKey
    com.couchbase.lite.internal.core.C4BlobReadStream
    com.couchbase.lite.internal.core.C4BlobStore
    com.couchbase.lite.internal.core.C4BlobWriteStream
    com.couchbase.lite.internal.core.C4Database
    com.couchbase.lite.internal.core.C4DatabaseObserver
    com.couchbase.lite.internal.core.C4DocEnumerator
    com.couchbase.lite.internal.core.C4Document
    com.couchbase.lite.internal.core.C4DocumentObserver
    com.couchbase.lite.internal.core.C4FullTextMatch
    com.couchbase.lite.internal.core.C4Key
    com.couchbase.lite.internal.core.C4Log
    com.couchbase.lite.internal.core.C4Prediction
    com.couchbase.lite.internal.core.C4Query
    com.couchbase.lite.internal.core.C4QueryEnumerator
    com.couchbase.lite.internal.core.C4RawDocument
    com.couchbase.lite.internal.core.C4Replicator
    com.couchbase.lite.internal.core.C4Socket
    com.couchbase.lite.internal.fleece.FLArray
    com.couchbase.lite.internal.fleece.FLArrayIterator
    com.couchbase.lite.internal.fleece.FLDict
    com.couchbase.lite.internal.fleece.FLDictIterator
    com.couchbase.lite.internal.fleece.FLEncoder
    com.couchbase.lite.internal.fleece.FLSliceResult
    com.couchbase.lite.internal.fleece.FLValue
    )
if(BUILD_ENTERPRISE)
    set(JNI_CLASSPATH
        ${JNI_CLASSPATH}
        ${PROJECT_SOURCE_DIR}/../../ee/common/main/java/)
    set(JNI_CLASSES
        ${JNI_CLASSES}
        com.couchbase.lite.internal.core.impl.NativeC4Listener
        com.couchbase.lite.internal.core.impl.NativeC4KeyPair
       )
endif()

create_javah(TARGET JNIHeaders
    CLASSES ${JNI_CLASSES}
    CLASSPATH ${JNI_CLASSPATH}
    OUTPUT_DIR ${JNI_OUTPUT_DIR}
    DEPENDS ${JNI_CLASSPATH})

#
# JNI for litecore - shared library including litecore static library
#

include_directories(
    ${JNI_OUTPUT_DIR}
    ${LITE_CORE_ROOT}/C/                                  # include c4Private.h for debugging
    ${LITE_CORE_ROOT}/C/include/
    ${LITE_CORE_ROOT}/LiteCore/Storage/
    ${LITE_CORE_ROOT}/LiteCore/Support/
    ${LITE_CORE_ROOT}/vendor/fleece/Fleece/
    ${LITE_CORE_ROOT}/vendor/fleece/Fleece/Mutable
    ${LITE_CORE_ROOT}/vendor/fleece/API
    ${LITE_CORE_ROOT}/vendor/fleece/Fleece/Core
    ${LITE_CORE_ROOT}/vendor/fleece/Fleece/Mutable
    ${LITE_CORE_ROOT}/vendor/fleece/Fleece/Support
    ${LITE_CORE_ROOT}/vendor/SQLiteCpp/include
    ${LITE_CORE_ROOT}/vendor/SQLiteCpp/sqlite3
    ${LITE_CORE_ROOT}/vendor/sqlite3-unicodesn
    ${LITE_CORE_ROOT}/vendor/mbedtls/include
    ${LITE_CORE_ROOT}/vendor/mbedtls/crypto/include)

if(BUILD_ENTERPRISE)
    add_definitions(-DCOUCHBASE_ENTERPRISE      # Tells LiteCore it's an EE build
                    -DSQLITE_HAS_CODEC)         # Enables SQLite encryption extension (SEE)
endif()

aux_source_directory(./main/cpp JNI_SRC)
add_library(LiteCoreJNI SHARED ${JNI_SRC})

# set dependency
add_dependencies(LiteCoreJNI JNIHeaders)

add_subdirectory(${LITE_CORE_ROOT} ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})

target_link_libraries(  LiteCoreJNI
                        ${WHOLE_LIBRARY_FLAG}
                        LiteCoreStatic
                        LiteCoreREST_Static
                        FleeceStatic
                        ${NO_WHOLE_LIBRARY_FLAG}
                        LiteCoreWebSocket
                        SQLite3_UnicodeSN
                        BLIPStatic
                        mbedcrypto
                        mbedtls
                        mbedx509
                        "atomic"
                        "log"
                        zlibstatic)

