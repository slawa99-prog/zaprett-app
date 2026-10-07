cmake_minimum_required(VERSION 3.22.1)
project(ethernetlink)
add_library(ethernetlink SHARED ethernetlink.c link_probe.c echo_probe.c echo_jni.c)
target_compile_options(ethernetlink PRIVATE -Wall -Wextra -Werror)
target_link_options(ethernetlink PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(ethernetlink android)
