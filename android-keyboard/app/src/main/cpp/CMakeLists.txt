cmake_minimum_required(VERSION 3.22)
project(bark_asr C)

# The prebuilts are fetched by tools/download-sherpa-onnx.sh and are not committed: they are
# ~26 MB of arm64 binaries for sherpa-onnx v2.0.1 (see docs/native-engine.md).
set(SHERPA "${CMAKE_CURRENT_SOURCE_DIR}/sherpa-onnx")
set(SHERPA_LIBS "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

if(NOT EXISTS "${SHERPA}/include/c-api.h")
  message(FATAL_ERROR
    "sherpa-onnx prebuilts are missing. Run tools/download-sherpa-onnx.sh from the module root.")
endif()

add_library(bark_asr SHARED bark_asr_jni.c)
target_include_directories(bark_asr PRIVATE "${SHERPA}/include")

# Both ship in the release tarball and are packaged from jniLibs/ next to libbark_asr.so.
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
  IMPORTED_LOCATION "${SHERPA_LIBS}/libonnxruntime.so")

add_library(sherpa_c_api SHARED IMPORTED)
set_target_properties(sherpa_c_api PROPERTIES
  IMPORTED_LOCATION "${SHERPA_LIBS}/libsherpa-onnx-c-api.so")

target_link_libraries(bark_asr sherpa_c_api onnxruntime log)
