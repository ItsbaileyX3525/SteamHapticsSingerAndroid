cmake_minimum_required(VERSION 3.22.1)
project(steamhaptics)

#as midifile.c is c and not cpp
add_library(steamhaptics SHARED
	haptics_native.cpp
	midifile/midifile.c
)

target_include_directories(steamhaptics PRIVATE
	midifile
)

find_library(log-lib log)

target_link_libraries(steamhaptics
	${log-lib}
)

#Android 15 alignment shit
if(ANDROID)
    target_link_options(steamhaptics PRIVATE "-Wl,-z,max-page-size=16384")
endif()
