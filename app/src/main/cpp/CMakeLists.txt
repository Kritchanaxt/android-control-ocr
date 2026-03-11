project(paddleocrncnn)

cmake_minimum_required(VERSION 3.4.1)

set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-mobile/sdk/native/jni)
find_package(OpenCV REQUIRED core imgproc)

set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

# ONNX Runtime configuration
set(ONNXRUNTIME_DIR ${CMAKE_SOURCE_DIR}/onnxruntime)
include_directories(${ONNXRUNTIME_DIR}/headers)
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION ${ONNXRUNTIME_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so)

add_library(paddleocrncnn SHARED paddleocr_ncnn.cpp common.cpp clipper.cpp)

target_link_libraries(paddleocrncnn ncnn onnxruntime ${OpenCV_LIBS} jnigraphics)

# ncnn forces -fno-rtti and -fno-exceptions on all targets that link to it via INTERFACE_COMPILE_OPTIONS.
# We must remove them from the target properties or append our own flags strongly.
set_target_properties(paddleocrncnn PROPERTIES COMPILE_FLAGS "-fexceptions -frtti")
# Fallback trick for CMake to ensure Exceptions override ncnn's flags
string(REPLACE "-fno-exceptions" "-fexceptions" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
string(REPLACE "-fno-rtti" "-frtti" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fexceptions -frtti")
