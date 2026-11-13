# 端侧 Embedding 原生库 (bge-small-zh via MNN)

`app/src/main/jniLibs/arm64-v8a/libembedjni.so` 由此目录构建:MNN 核心 `Interpreter`
跑 bge-small-zh 编码器,CLS 池化 + L2 归一,对接 `EmbeddingEngine.kt`。

## 组成
- `embed_jni.cpp` —— JNI 桥(`nativeEmbedLoad/Embed/Release`),**复用** app 已有的
  `libMNN.so` / `libMNN_Express.so`(零新增核心库,只多一个 ~260KB 的 `libembedjni.so`)。

## 重建(Linux + NDK r27)
```bash
export NDK=/home/zhao/android-kernel/prebuilts/ndk-r27
cmake -G Ninja -S native/mnn-embed -B native/mnn-embed/build \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
  -DMNN_ROOT=/path/to/MNN   # 仅需其 include/ 头文件;.so 从 app jniLibs 链接
cmake --build native/mnn-embed/build
cp native/mnn-embed/build/libembedjni.so app/src/main/jniLibs/arm64-v8a/
```
`MNN_ROOT` 指向任一 MNN 源码 checkout(只用 `include/`)。链接的 `libMNN.so`/`libMNN_Express.so`
来自 app jniLibs,保证与出包 ABI 一致。

## 模型
`app/src/main/assets/embed/bge_fp16.mnn`(47.5MB,git-lfs)+ `vocab.txt`。转换配方见
`docs/embedding-integration-design.md` §12。首次启动拷贝到 filesDir 供 MNN 按路径加载。
