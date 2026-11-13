# 端侧 Embedding 模型分析与召回质量基准

> 目的:为聊天记忆检索(`MemoryRetrieval` 的 relevance 分量)评估「是否值得用
> embedding 替换现有本地词法(lexical)召回」,并在候选模型里选型。
>
> 结论先行:**值得上 embedding(语义 query 上 lexical 几乎失效)。运行时优先 LiteRT →
> 选 EmbeddingGemma-300m(LiteRT 原生、中文强);若改为体积/延迟优先则 bge-small-zh-v1.5(int8,复用 MNN)。
> all-MiniLM-L6-v2 虽是 LiteRT 最省事现货,但中文太差(比 lexical 还低),出局。**
>
> 设备目标:ELP-AN00(骁龙 8s Gen3 + Adreno 735 + 16GB + Android 16),中文短文本、
> 端侧本地、低延迟、隐私。最后更新:2026-11-13。

---

## 1. 候选模型分析(端侧 + 中文短文本)

该设备对所有候选都毫无算力压力,因此按「中文检索质量 / 体积 / 端侧现货 / 能否复用 MNN」排序,而非能否跑动。

| 模型 | 参数 | 维度 | 量化体积 | C-MTEB 检索分 | 序列长 | 端侧现货 | 适配本项目 |
|---|---|---|---|---|---|---|---|
| **bge-small-zh-v1.5** | 24M | 512 | ONNX int8 **~24MB** | 61.77 | 512 | ✅ 多个 ONNX int8 | MNN 易转,零额外 .so |
| **gte-small-zh** | 30M | 512 | int8 ~100MB 内 | **65.50** | 512 | 需自转 ONNX | MNN 易转 |
| multilingual-e5-small | 118M | 384 | int8 ~110MB | 59.95(中文偏弱) | 512 | ✅ ONNX | 一般 |
| **Qwen3-Embedding-0.6B** | 0.6B | 32–1024(MRL) | GGUF Q8 639MB / Q4 ~350MB | **71.03** | 32K | ✅ 官方 GGUF | 偏重,需转 |
| EmbeddingGemma-2 文本版 | 270M | 768(MRL→128) | LiteRT int4 **~165MB** | ~58(cmn 0.5815) | 8192 | ✅ LiteRT/MediaPipe | ❌ 不能复用 MNN |
| multilingual-e5 / m3e / text2vec | 110M 级 | 384–768 | ~400MB(需自转) | 38–60 | 512 | 部分 | 一般 |

数据来源:综述 arXiv:2504.15965;bge/gte/e5 → HF 模型卡 + C-MTEB 榜;Qwen3 → arXiv:2506.05176 +
官方 GGUF;EmbeddingGemma-2 → litert-community 模型卡 + github.com/Weidows/embeddinggemma2-zh。

### 运行时选型(本项目已定:**优先 LiteRT**)

按项目决策,推理引擎优先采用 **LiteRT**(即原 TFLite,Google 端侧运行时;MediaPipe Text Embedder /
LiteRT-LM / 即将上线的 ML Kit 均基于它,支持 CPU/GPU/**NPU** 加速)。这会改变模型可选集 ——
LiteRT 优先意味着「模型必须有 `.tflite`/`.litertlm` 现货或可转」:

| 模型 | LiteRT 现货 | 说明 |
|---|---|---|
| **EmbeddingGemma-300m / -2** | ✅ `.litertlm` 官方现货 | MediaPipe/ML Kit 原生,tokenizer 自动,NPU 加速,中文强 |
| all-MiniLM-L6-v2 | ✅ MediaPipe 现货 | 最省事,但**中文太差**(见基准),出局 |
| USE-Lite | ✅ MediaPipe 现货 | 维度低、中文弱,出局 |
| bge-small-zh / gte-small-zh | ❌ 需转 | ONNX→TFLite(ai-edge-torch / onnx2tf)+ 自己打包 BertTokenizer metadata,可行但非文档化路径 |

**运行时优先级(本项目)**:
1. **LiteRT(MediaPipe Text Embedder / LiteRT-LM)** —— 首选。EmbeddingGemma 原生、tokenizer 自动、
   NPU 加速、无需 NDK/自写 native。代价:额外 MediaPipe native 体积(与现有 MNN 不共享)、模型选择受限于 LiteRT 现货或自转。
2. 复用 MNN(零额外 .so)/ ONNX Runtime Mobile(抄 `shubham0204/Sentence-Embeddings-Android`)—— 仅在
   坚持用 bge-small-zh 这类非 LiteRT 原生模型、且在意 .so 体积时的备选。
3. llama.cpp / ExecuTorch —— 排后(功能重复 / 生态不成熟)。

模型在 8s Gen3 上:small 级 CPU int8 约数十毫秒;EmbeddingGemma-300m 约 ~200ms 量级(MediaPipe,
Galaxy S26 Ultra 4 线程参考)。记忆检索是低频离线调用,**CPU/NPU 都够,延迟不是决策变量** ——
真正的问题是召回质量,故做下面的基准。

---

## 2. 召回质量基准

### 方法
- **语料**(`corpus.json`):一个连贯人设(北京后端程序员、成都人)的 **40 条记忆**
  (事实/偏好/画像/风格,对标真机所见风格)+ **28 条 query**(对方在聊天里说的话)+ 人工相关性标注。
- **自动分类**:对每条 query,若其与标准答案记忆有**共享 token**(按 Kotlin 的 CJK bigram 分词)
  则记为「词面可达」,否则「纯语义」。结果:**词面可达 13 / 纯语义 15**(近 50/50)。
- **lexical**:严格复刻 `app/.../memory/MemoryRetrieval.kt` 的 `tokenize` + `LexicalRelevance`
  (CJK bigram + content 词面重叠打分)。
- **embedding**:bge-small-zh / gte-small-zh / Qwen3-0.6B,句向量归一化后按余弦排序;
  bge 用官方 query 指令前缀,Qwen3 用 `prompt_name="query"`。
- **只测 relevance 排序**,刻意排除 recency/importance(与静态语料无关),以隔离要替换的那一个组件。
- 指标:hit@1、recall@3、recall@5、MRR@10。

### 结果

| 方法 | hit@1 | recall@3 | recall@5 | MRR@10 | 维度 | LiteRT 原生 |
|---|---|---|---|---|---|---|
| **lexical(现有)** | 0.429 | 0.482 | 0.571 | 0.495 | — | — |
| **bge-small-zh-v1.5** | **0.893** | 0.911 | 0.946 | **0.940** | 512 | ❌ 需转 |
| gte-small-zh | 0.857 | 0.946 | 0.982 | 0.923 | 512 | ❌ 需转 |
| **all-MiniLM-L6-v2** | 0.214 | 0.339 | 0.375 | 0.297 | 384 | ✅ 现货 |
| Qwen3-Emb-0.6B | 0.893 | 0.946 | 0.982 | 0.940 | 1024 | ❌ |
| **EmbeddingGemma-300m** | 0.857 | 0.893 | 0.982 | 0.920 | 768 | ✅ 现货 |

**按 query 类型拆分(recall@3)—— 决定性对比:**

| 方法 | 词面可达 | 纯语义 |
|---|---|---|
| **lexical(现有)** | 0.885 | **0.133** |
| bge-small-zh | 0.885 | 0.933 |
| gte-small-zh | 0.962 | 0.933 |
| all-MiniLM-L6-v2 | 0.577 | **0.133** |
| Qwen3-0.6B | 0.923 | 0.967 |
| **EmbeddingGemma-300m** | 0.923 | 0.867 |

> all-MiniLM-L6-v2 经 `sentence-transformers/all-MiniLM-L6-v2` 实测;EmbeddingGemma-300m(v1)
> 因 google 官方库 gated(401),经未门控镜像 `unsloth/embeddinggemma-300m` 实测,用官方 query/document
> 指令。两者质量与所跑运行时无关(embedding 为确定性计算),故 PC 结果对 LiteRT 部署同样成立(量化误差除外)。

---

## 3. 结论

1. **embedding 值得上。** 现有 lexical 在词面可达 query 上不差(0.885),但在纯语义 query 上几乎失效
   (**0.133**);真实对话约一半是纯语义(同义/转述/世界知识)。好的 embedding 整体 hit@1 **0.43 → 0.86–0.89**。
2. **all-MiniLM-L6-v2 出局。** 它整体比 lexical 还差(hit@1 0.214),纯语义 0.133,词面也只有 0.577 ——
   英文训练模型对中文不适用。虽是 LiteRT 最省事的现货,也不能用。
3. **LiteRT 优先路线 → EmbeddingGemma-300m。** 它是唯一「LiteRT 原生 + 中文强」的选项:hit@1 0.857、
   纯语义 0.867,与 gte-small-zh 持平、仅比 bge-small-zh 低约 4 点;且 MediaPipe/ML Kit 原生、tokenizer 自动、
   NPU 加速、无需自写 native。代价是体积较大(int4 ~165–240MB)、单条 ~200ms —— 对低频记忆检索可接受。
4. **若改为体积/延迟优先(放弃 LiteRT 约束)→ bge-small-zh-v1.5(int8)** 仍是质量/体积之王
   (hit@1 0.893、int8 ~24MB、可复用 MNN、零额外 .so),但需 ONNX→TFLite 自转才能上 LiteRT。
   Qwen3-0.6B 质量最高但 0.6B/1024 维过重,性价比不足。

**落地建议(按本项目 LiteRT 优先)**:relevance 分量从 lexical 升级为 **EmbeddingGemma-300m**,
走 **MediaPipe Text Embedder / LiteRT** 路线;`MemoryRetrieval.RelevanceScorer` 已是可插拔接口,
只需加一个调用 LiteRT embedder 的实现,并把向量存入 `MemoryEntity`(Phase 1 预留了升级路径)。
备选:若后续更在意体积/延迟,可改用 bge-small-zh-v1.5(int8)自转 TFLite 或复用 MNN。

---

## 4. 边界与局限(重要)

- **合成语料 + 作者标注**:40 记忆 / 28 query 均按真机风格手写,样本小、相关性标签由作者定,有偏差。
  **词面/语义 50/50 的比例是设计选择**;若真实 query 以词面重叠为主,lexical 的劣势会被高估。
  要更准需用真实对话语料复测。
- **PC 上 fp32/fp16/bf16 质量,非端侧 int8**:量化通常掉 ~1–1.5%,不改排名,但端侧实际略低。
- **未测端侧延迟/内存**:本基准只测召回质量;延迟/内存需在 ELP-AN00 上单独实测(见下)。
- **纯 relevance 排序**:App 内 relevance 会与 recency+importance 组成三因子分,线上表现还取决于权重。
- **EmbeddingGemma 测的是 v1 300m、且走非官方镜像**:google 官方库 gated(需接受许可+token),故用
  `unsloth/embeddinggemma-300m` 镜像实测;与官方权重应一致但未逐位核对。端侧实际推荐的 **EmbeddingGemma-2
  文本版 270m** 未在本语料实测(其 cmn C-MTEB ~0.5815,量级相近),建议集成前用同一语料补测一次。
- **all-MiniLM 用官方 ST 权重**,结果可信;其"中文差"是模型本质(英文训练),非实现问题。

---

## 5. 复现

```bash
python3 -m venv venv && . venv/bin/activate
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install "sentence-transformers>=3.0"
python bench.py        # 首次运行会从 HF 下载 3 个模型(约 1.4GB)
```

产物:`results.json`(指标)、`corpus.json`(语料 + 自动类型标注)。`bench.py` 内含语料与
lexical 复刻实现。未 `trust_remote_code`,仅下载模型权重。

## 6. 待办
- [ ] 用同一语料补测 EmbeddingGemma-2 文本版 270m(`.litertlm`),确认与 v1 300m 质量一致
- [ ] 真机延迟/内存实测(EmbeddingGemma via MediaPipe Text Embedder,ELP-AN00,含 NPU)
- [ ] 集成 MediaPipe Text Embedder,实现 `RelevanceScorer` 的 LiteRT embedding 版,向量存入 `MemoryEntity`
- [ ] 备选评估:bge-small-zh ONNX→TFLite 自转 + BertTokenizer 打包(若改走体积优先)
- [ ] 用真实对话语料复测召回质量
