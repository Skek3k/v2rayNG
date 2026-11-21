cmake_minimum_required(VERSION 3.22.1)
project("ruroa")

add_library(
        ruroa
        SHARED
        ruroa_guard.cpp
)

set_target_properties(ruroa PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON
)

target_compile_options(ruroa PRIVATE
        -O3
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
)

target_link_options(ruroa PRIVATE
        -Wl,--gc-sections
        -s
)

find_library(log-lib log)
target_link_libraries(ruroa ${log-lib})
