cmake_minimum_required(VERSION 3.10)
project(llmjni)
set(CMAKE_CXX_STANDARD 17)

# Headers from an MNN source checkout (core include/ + llm engine include/).
# Override with -DMNN_ROOT=... ; defaults to a sibling checkout.
if(NOT DEFINED MNN_ROOT)
    set(MNN_ROOT /tmp/MNN-headers)
endif()

# Link against the SAME prebuilt engine libs the app ships, so the ABI matches
# the model runtime. Only this JNI bridge is rebuilt; the engine libs are not.
set(JNILIBS ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/jniLibs/arm64-v8a)
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION ${JNILIBS}/libMNN.so)
add_library(MNN_Express SHARED IMPORTED)
set_target_properties(MNN_Express PROPERTIES IMPORTED_LOCATION ${JNILIBS}/libMNN_Express.so)
add_library(llm SHARED IMPORTED)
set_target_properties(llm PROPERTIES IMPORTED_LOCATION ${JNILIBS}/libllm.so)

add_library(llmjni SHARED llm_jni.cpp)
target_include_directories(llmjni PRIVATE
    ${MNN_ROOT}/include
    ${MNN_ROOT}/transformers/llm/engine/include)
target_link_libraries(llmjni llm MNN MNN_Express log android)
