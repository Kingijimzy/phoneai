cmake_minimum_required(VERSION 3.22.1)
project(orionpty C)

add_library(orionpty SHARED native_pty.c)
target_compile_features(orionpty PRIVATE c_std_11)
target_compile_definitions(orionpty PRIVATE _GNU_SOURCE)
target_compile_options(orionpty PRIVATE -Wall -Wextra -Werror=implicit-function-declaration)
find_library(log-lib log)
target_link_libraries(orionpty ${log-lib})
