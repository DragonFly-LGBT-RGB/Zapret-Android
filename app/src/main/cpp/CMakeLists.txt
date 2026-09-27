cmake_minimum_required(VERSION 3.22.1)

project(zapret_native C)

set(BYEDPI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/byedpi)

if (EXISTS ${BYEDPI_DIR}/main.c)
    message(STATUS "byedpi sources found, building the real engine")

    file(GLOB BYEDPI_SRC ${BYEDPI_DIR}/*.c)
    list(REMOVE_ITEM BYEDPI_SRC ${BYEDPI_DIR}/win_service.c)

    add_library(byedpi SHARED ${BYEDPI_SRC} byedpi_jni.c)
    target_include_directories(byedpi PRIVATE ${BYEDPI_DIR})
    target_compile_options(byedpi PRIVATE -std=gnu99 -O2 -Wall -Wno-unused -Wno-unused-parameter)
    target_compile_definitions(byedpi PRIVATE ANDROID_APP HAVE_BYEDPI=1)
    target_link_libraries(byedpi PRIVATE android log)
else ()
    message(WARNING "byedpi sources are missing: building a stub. Run scripts/fetch-native-sources.sh")

    add_library(byedpi SHARED byedpi_stub.c)
    target_compile_options(byedpi PRIVATE -std=gnu99 -O2)
    target_link_libraries(byedpi PRIVATE android log)
endif ()

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
