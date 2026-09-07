cmake_minimum_required(VERSION 3.22.1)

project("dhd_adb")

set(CMAKE_CXX_STANDARD 17)
add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti)

find_package(boringssl REQUIRED CONFIG)

add_library(dhd_adb SHARED adb_pairing.cpp)
target_link_libraries(dhd_adb log boringssl::crypto_static c++_shared)
