cmake_minimum_required(VERSION 3.10.2)
project(slipffi CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Shim JNI da FFI (carregado por NativeBridge via System.loadLibrary("slipffi")).
add_library(slipffi SHARED slipffi.cpp)
target_link_libraries(slipffi dl)

# Biblioteca de demonstração/testes (empacotada como libslipdemo.so).
add_library(slipdemo SHARED demo/slipdemo.cpp)
