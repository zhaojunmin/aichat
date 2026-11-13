# 本地 LLM 引擎 A/B:MNN(现状) vs LiteRT-LM

> 问题:把 App 的本地 LLM 推理从 **MNN** 换成 **LiteRT-LM** 是否值得?
> 结论先行:**在目标设备上不值得(为性能)。MNN 的 decode 比 LiteRT 快 ~1.7–2.5×;
> 唯一迁移理由是架构统一(一套 LiteRT 跑 LLM+embedding、扔掉 ~108MB MNN .so、免 NDK),
> 那是工程整洁性,不是用户可感的性能。**
>
> 模型受控:两边都是 **Qwen2.5-1.5B-Instruct**(App 默认本地模型)。
> 设备:ELP-AN00 = 骁龙 8s Gen3(SM8635)+ Adreno 735 + 16GB + Android 16。最后更新:2026-11-13。

---

## 1. 背景与为何做

现状:`LocalLlmEngine`(`app/.../data/local/`)用 MNN 跑 Qwen2.5-1.5B **Q4**(.mnn,CPU-only,4 个 .so 共 ~108MB,JNI)。
若统一到 LiteRT(embedding 已倾向 EmbeddingGemma/LiteRT),理论收益是 GPU/NPU 加速 + 一套运行时。
但先验分析指出两点风险:① **8s Gen3 不在 LiteRT 官方 NPU 名单** → 只能 CPU/GPU;② 官方 `litert-community`
的 Qwen2.5-1.5B 只有 **q8**(无 int4),而 decode 是内存带宽瓶颈 → q8 权重流量是 Q4 的 2 倍。
故实测验证。

## 2. 方法

同机、同模型(Qwen2.5-1.5B),只比引擎:

- **LiteRT 侧**:极简 harness app(`:litertbench`,官方 `com.google.ai.edge.litertlm:litertlm-android:0.19.1`,纯 AAR),
  用官方 `benchmark()`(prefill 128 + decode 128),分别测 **CPU(4线程)** 与 **GPU(Adreno)**。模型 `.litertlm` q8
  来自 `litert-community/Qwen2.5-1.5B-Instruct`(1.6GB)。
- **MNN 侧**:同一个 harness,经 FQN 对齐的 JNI 类直接驱动 App 自带的 MNN `.so`(复用 `libllmjni.so` 的
  `nativeLoad/nativeChat/nativeRelease`),模型为 App 用的 `MNN/Qwen2.5-1.5B-Instruct-MNN`(Q4,CPU 4线程)。
  `nativeChat` 内部 `llm->reset()` → 无状态,故用**两次调用相减**隔离 decode:`maxTokens=1`(≈prefill+1)与
  `maxTokens=200`,`decode tok/s = (N_200 − 1)/(T_200 − T_1)`。生成文本用 **Qwen2.5 tokenizer** 在 PC 上精确计数(N=201)。
- 设备 logcat 受限(Honor 加密),结果经 app 内 TextView + uiautomator 文本双取证。

## 3. 结果(同机,Qwen2.5-1.5B)

| 引擎 / 后端 | 量化 | **decode tok/s** ⭐ | prefill tok/s | 加载 | PSS(见注) |
|---|---|---|---|---|---|
| **MNN · CPU 4线程** | **Q4** | **30.2** | ~160(未单独隔离) | 1.1s | 980MB(模型常驻) |
| LiteRT-LM · CPU 4线程 | q8 | 12.1(暖) / 6.5(冷) | 61 | 1.7s | 511MB* |
| LiteRT-LM · GPU(Adreno 735) | q8 | 17.5 | 380 | 14.2s | 317MB* |

\* LiteRT 的 PSS 是 `benchmark()` 跑后读的,且 q8 模型走 mmap 零拷贝、未完整计入 PSS → **偏低、不可直接比**。
按磁盘/常驻,q8(1.6GB)> Q4(821MB),LiteRT 实际内存更大。

注:decode 为关键指标(聊天是 decode-bound)。MNN decode 经 PC tokenizer 精确计数(200 个 decode token)。
LiteRT 冷/暖差异大(CPU 6.5→12.1),说明单次测量有噪声;暖态为准。

## 4. 解读

- **MNN Q4 decode(30.2)≈ LiteRT CPU(12.1)的 2.5×、LiteRT GPU(17.5)的 1.7×。** 聊天是 decode-bound,MNN 明显更快。
- 主因:① **Q4 vs q8** —— decode 受内存带宽限制,q8 权重流量翻倍直接拖慢;官方 1.5B 无 int4 现货。
  ② MNN 的 ARM 内核对该芯片调校好;③ **无 NPU**,LiteRT 拿不到杀手锏。
- LiteRT GPU 唯一赢点是 **prefill(380 vs ~160)**,但短回复 prefill 不是瓶颈,且 GPU 加载要 14s。
- 这与先验的证据级判断完全一致。

## 5. 结论与建议

**不要为性能/体验把本地 LLM 迁到 LiteRT。** 在 8s Gen3 上,迁移会让 decode 变慢、内存变大(q8)。

迁移**仅**在把整个端侧栈统一到 LiteRT(LLM + EmbeddingGemma 共用一套运行时、移除 MNN .so/NDK)作为明确
战略目标时才考虑,且需接受 decode 退化。embedding 上 LiteRT(EmbeddingGemma)与 LLM 留在 MNN 可以共存,
不必为 embedding 迁 LLM。

若未来仍想上 LiteRT LLM 且不想丢性能:需自行用 litert-torch 把 Qwen2.5-1.5B 转 **int4**(研究指出有 OOM/
质量偏差坑,须自测),把 decode 差距补回来后再评估。

## 6. 局限

- 单设备、单次(LiteRT 冷暖差异显著);正式结论宜多跑几次取中位数。
- q8 vs Q4 非完全对等 —— 但这是 LiteRT 现货的真实约束,本身即迁移代价,非测试缺陷。
- MNN prefill 未单独隔离(只有 TTFT≈0.25s 的合并值);decode(关键项)是精确的。
- 内存 PSS 因 mmap/释放时机不可直接横比,仅定性:LiteRT q8 常驻 > MNN Q4。

## 7. 复现

- LiteRT:`:litertbench` 模块(一次性,未提交),`litertlm-android:0.19.1`,`benchmark()`;模型 `litert-community/Qwen2.5-1.5B-Instruct` 的 `*_q8_ekv4096.litertlm`。
  - 坑:该 AAR 是 Kotlin 2.4.0 metadata,AGP9 内置 2.2 编译器需加 `-Xskip-metadata-version-check`,并 force `kotlin-stdlib/reflect:2.2.10`;`BenchmarkInfo` 字段用反射读。
- MNN:复用 App 的 4 个 `.so` + FQN 对齐的 `com.octos.aichat.data.local.LocalLlmEngine`;模型 `MNN/Qwen2.5-1.5B-Instruct-MNN`。
- decode tok/s:两次调用(max 1 vs 200)相减,输出用 Qwen2.5 tokenizer 精确计数。
