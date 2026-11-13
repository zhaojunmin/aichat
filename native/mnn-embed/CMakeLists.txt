cmake_minimum_required(VERSION 3.10)
project(embedjni)
set(CMAKE_CXX_STANDARD 17)

# Headers from an MNN source checkout (only include/ is needed for the core
# Interpreter/Tensor API). Override with -DMNN_ROOT=... if elsewhere.
if(NOT DEFINED MNN_ROOT)
    set(MNN_ROOT /home/zhao/mnn_spike/MNN)
endif()

# Link against the SAME prebuilt MNN libs the app ships, so ABI matches.
set(JNILIBS ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/jniLibs/arm64-v8a)
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION ${JNILIBS}/libMNN.so)
add_library(MNN_Express SHARED IMPORTED)
set_target_properties(MNN_Express PROPERTIES IMPORTED_LOCATION ${JNILIBS}/libMNN_Express.so)

add_library(embedjni SHARED embed_jni.cpp)
target_include_directories(embedjni PRIVATE ${MNN_ROOT}/include)
target_link_libraries(embedjni MNN MNN_Express log android)
