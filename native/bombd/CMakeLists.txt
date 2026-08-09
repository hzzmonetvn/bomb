cmake_minimum_required(VERSION 3.22.1)
project(bombd C)

add_executable(bombd bombd.c)
target_compile_features(bombd PRIVATE c_std_17)
target_compile_options(bombd PRIVATE -Wall -Wextra -Werror -fstack-protector-strong)
target_link_options(bombd PRIVATE -Wl,-z,relro,-z,now)
