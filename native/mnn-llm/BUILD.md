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
