#!/usr/bin/env bash
# 拉 llama.cpp 源码到 app/src/main/cpp/llama.cpp
#
# 为什么不把源码提交进仓库：它是几百兆，而且 Android 侧的 JNI 接口和 CMake
# 变量每几个版本就动一次，跟着上游跑比锁一份本地副本更省心。
#
# 版本在这里锁死。llm_jni.cpp 里用到的 API（llama_model_load_from_file /
# llama_init_from_model / llama_sampler_*) 都是 v0.7.0 确认存在的，
# 换版本前先回去核对 include/llama.h。
set -euo pipefail

VERSION="${LLAMA_VERSION:-v0.7.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/cpp/llama.cpp"

if [ -f "$DEST/include/llama.h" ]; then
    echo "[llama] 已存在，跳过：$DEST"
    exit 0
fi

echo "[llama] 拉取 $VERSION -> $DEST"
# --depth 1 只取一个快照。
# 不带 --recursive：llama.cpp 没有 KleidiAI 子模块，那份源码是 CMake 用
# FetchContent 从 ARM-software/kleidiai 下 tarball 拿的，构建时自动完成。
git clone --depth 1 --branch "$VERSION" \
    https://github.com/ggml-org/llama.cpp "$DEST"

echo "[llama] 完成，$(du -sh "$DEST" | cut -f1)"
