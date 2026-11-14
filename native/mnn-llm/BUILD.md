# 本地推理原生库 (MNN + Qwen2.5)

`app/src/main/jniLibs/arm64-v8a/` 下的 4 个 `.so`(经 Git LFS 存储)由此目录的 JNI 桥
加 MNN 源码构建而来。平时无需重建;仅在升级 MNN 版本时按下述步骤重出。

## 组成
- `libMNN.so` / `libMNN_Express.so` / `libllm.so` —— MNN 推理引擎 + LLM(CPU-only 构建)
- `libllmjni.so` —— 本仓库的 JNI 桥(`llm_jni.cpp`),对接 `LlmEngine.kt`

## 只改 JNI 桥时的重建(常见:改 `llm_jni.cpp` 或 `LlmEngine` 的包名/FQN)
`libMNN*.so` / `libllm.so` 是引擎库,与 Java FQN 无关,**无需重建**;只有
`libllmjni.so` 里的 `Java_...` 导出符号按声明类的全限定名做 name-mangling,
类改名/挪包后必须重出。只需 MNN 头文件 + 现成引擎库即可链接:

```bash
export NDK=$ANDROID_HOME/ndk/27.3.13750724
CM=$ANDROID_HOME/cmake/3.22.1/bin
git clone --depth 1 https://github.com/alibaba/MNN /tmp/MNN-headers   # 仅取 include/

$CM/cmake -G Ninja -S native/mnn-llm -B native/mnn-llm/build \
  -DCMAKE_MAKE_PROGRAM=$CM/ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DMNN_ROOT=/tmp/MNN-headers
$CM/cmake --build native/mnn-llm/build
cp native/mnn-llm/build/libllmjni.so app/src/main/jniLibs/arm64-v8a/
# 校验:llvm-nm -D libllmjni.so | grep Java_   → 应为新 FQN 符号
```
CMakeLists 已把引擎库指向 `app/src/main/jniLibs/arm64-v8a/`,保证 ABI 与运行时一致。

**头文件版本要和引擎库一致。** 现有引擎库构建于 MNN `ff0250e`(2026-11-12)。用 MNN 主干的头文件
编译时,虚函数调用(如 `load()`)按头文件里的虚表槽位分派,版本不一致就可能调错函数。只取头文件:

```bash
git init MNN-headers && cd MNN-headers && git remote add origin https://github.com/alibaba/MNN
git sparse-checkout set include transformers/llm/engine/include
git fetch --depth 1 origin ff0250ef9b5dc21fbf7b188e63f4ac1711afa0a1 && git checkout FETCH_HEAD
```

新增对引擎虚函数的调用时,优先用限定名(`llm->Llm::generate_init(...)`)直接绑定导出符号,不经过虚表。

## 生成超时
`nativeLoad` 把 `LlmEngine.GENERATION_TIMEOUT_MS` 通过 `set_config({"timeout_ms":…})` 设给引擎;引擎在
解码循环里逐 token 检查「预填充 + 解码」耗时,到时即停。超时后引擎处于 `TIMEOUT` 状态,
`response(ChatMessages)` 会直接返回空;`reset()` 不清这个状态,所以 `nativeChat` 每次先调
`generate_init()` 复位。

## 升级 MNN 引擎版本时(需重出引擎库)
```bash
git clone --depth 1 https://github.com/alibaba/MNN
cmake -G Ninja -S MNN -B MNN/build-cpu \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
  -DMNN_BUILD_LLM=ON -DMNN_OPENCL=OFF -DMNN_ARM82=ON -DMNN_SEP_BUILD=ON
cmake --build MNN/build-cpu --target llm -j
# 拷贝 libMNN.so / libMNN_Express.so / libllm.so 到 jniLibs 后再按上面重出 libllmjni.so
```

## 模型
运行时模型(Qwen2.5-3B-Instruct, MNN Q4, ~2.3GB)不入库,由 `ModelStore`
首次启用本地模式时从 taobao-mnn 下载到 app filesDir。
