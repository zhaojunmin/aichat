# 本地推理原生库 (MNN + Qwen2.5)

`app/src/main/jniLibs/arm64-v8a/` 下的 4 个 `.so`(经 Git LFS 存储)由此目录的 JNI 桥
加 MNN 源码构建而来。平时无需重建;仅在升级 MNN 版本时按下述步骤重出。

## 组成
- `libMNN.so` / `libMNN_Express.so` / `libllm.so` —— MNN 推理引擎 + LLM(CPU-only 构建)
- `libllmjni.so` —— 本仓库的 JNI 桥(`llm_jni.cpp`),对接 `LocalLlmEngine.kt`

## 重建步骤(Linux + NDK r27)
```bash
# 1) 构建 MNN(CPU-only;实测本机 Adreno GPU 无收益,故关闭 OpenCL)
git clone --depth 1 https://github.com/alibaba/MNN
cmake -G Ninja -S MNN -B MNN/build-cpu \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
  -DMNN_BUILD_LLM=ON -DMNN_OPENCL=OFF -DMNN_ARM82=ON -DMNN_SEP_BUILD=ON
cmake --build MNN/build-cpu --target llm -j

# 2) 构建 JNI 桥(改 CMakeLists.txt 里的 MNN_ROOT 指向上面的 MNN)
cmake -G Ninja -S native/mnn-llm -B native/mnn-llm/build \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
cmake --build native/mnn-llm/build

# 3) 拷贝 4 个 .so 到 app/src/main/jniLibs/arm64-v8a/
```

## 模型
运行时模型(Qwen2.5-3B-Instruct, MNN Q4, ~2.3GB)不入库,由 `LocalModelManager`
首次启用本地模式时从 taobao-mnn 下载到 app filesDir。
