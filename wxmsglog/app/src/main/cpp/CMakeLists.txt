# llama.cpp 的安卓构建壳。
#
# 这里不把 llama.cpp 源码放进仓库：它有一百多兆，而且跟着上游跑比锁死一份
# 本地副本更省心。构建前由 CI 执行 tools/clone_llama.sh 拉到 cpp/llama.cpp。
#
# 只编 llama 本体，不编 common / tools / server / tests——那些对推理没用，
# 编进来纯属让构建时间翻倍。

cmake_minimum_required(VERSION 3.22.1)

project(llmjni C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/llama.cpp")

if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR
        "找不到 llama.cpp 源码：${LLAMA_SRC}\n"
        "先在工程根目录执行 tools/clone_llama.sh 把源码拉下来。")
endif()

# AGP 给 release 传的是 -O2，对 ggml 的矩阵乘来说太保守。
# 这里在 add_subdirectory 之前覆盖，改的是全局的 Release 选项，llama.cpp 也跟着吃到 -O3。
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
    set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
endif()

# ---- ARM 指令集：这是最划算的一项 ----
#
# NDK 默认按 armv8-a 编，只有基础 NEON。而 llama.cpp 的量化 kernel 是靠
# __ARM_FEATURE_DOTPROD / __ARM_FEATURE_FP16_VECTOR_ARITHMETIC 这些宏来
# 切换实现的——宏没定义就退回软件模拟，同一条矩阵乘要跑好几倍的指令。
#
# 2018 年之后的中高端 SoC 基本都支持 dotprod（int8 点积）和 fp16。
#
# 预期收益：量化推理 1.5~2 倍。
#
# ⚠️ 这一项是有运行时风险的，不能当成「纯编译期优化」看：编译期定了指令集，
# 运行设备不支持就是 SIGILL 闪退，没有运行时判断的余地。极少数廉价 4G 机型
# 用的是 armv8.0 的 A53 核心，没有 dotprod。自用没问题；要分发给别人时，
# 要么去掉 -march 出一版通用包，要么按 CPU 特性分包。
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+dotprod+fp16)
endif()

# ---- 关掉一切用不上的东西 ----
# 静态链接进 libllmjni.so，最终 APK 里只有一个 .so，少一层加载开销
set(BUILD_SHARED_LIBS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON      OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL              OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV      OFF CACHE BOOL "" FORCE)

# OpenMP 在安卓上要把 libomp 一起塞进来，收益不确定，先关掉。
set(GGML_OPENMP             OFF CACHE BOOL "" FORCE)

# KleidiAI 是 ARM 官方的微内核库，能再快 10~30%。但它是 git 子模块，
# 需要 clone_llama.sh 带 --recursive，CI 缓存里那份可能没有子模块。
# 所以这条先关着：等 -march 这一版的包验证稳定了，再单独开一轮。
# 要开的话：clone 脚本改成 --recursive，这里改成 ON。
set(GGML_CPU_KLEIDIAI       OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE          OFF CACHE BOOL "" FORCE)

# GPU 后端（Vulkan）也先不编。原因不是不想用，是 Android 上编它要先有 glslc
# 把 shader 编成 spv，CI 镜像里没有，得额外装 shaderc；而且 Vulkan 驱动在
# 国产 ROM 上差异很大，编进去之后还要在真机上逐个验证。这些留到下一轮。
# set(GGML_VULKAN          ON  CACHE BOOL "" FORCE)

# 交叉编译时不能让 cmake 去探测宿主机 CPU 指令集，否则会编出 x86 指令
set(GGML_NATIVE             OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/llama-build")

# ---- JNI 桥 ----

add_library(llmjni SHARED llm_jni.cpp)

target_include_directories(llmjni PRIVATE
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include")

target_link_libraries(llmjni
    llama
    android
    log)

# 安卓 15 起 64 位库要按 16KB 对齐
set_target_properties(llmjni PROPERTIES
    LINK_FLAGS "-Wl,-z,max-page-size=16384")
