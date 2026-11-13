cmake_minimum_required(VERSION 3.22.1)
project(evgrab C)
add_library(evgrab SHARED evgrab.c)
target_link_libraries(evgrab log)
