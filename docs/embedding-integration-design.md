# 端侧 Embedding 集成设计(bge-small-zh / 复用 MNN)

> 把记忆检索的 relevance 分量从**本地词法**升级为**语义 embedding**,落地到 App。
> 选型已定(见 [`embedding-benchmark/`](embedding-benchmark/README.md)):
> **bge-small-zh-v1.5(int8)+ 复用现有 MNN**。
>
> 为何不是 EmbeddingGemma/LiteRT:① embedding 基准里 bge-small-zh 质量最高(hit@1 0.893)、
> 体积最小(int8 ~24MB);② [本地 LLM A/B](local-llm-litert-ab.md) 证实这台设备(8s Gen3,无 NPU)
> 上 LiteRT 无性能优势、LLM 应留 MNN —— 那么 embedding 也用 MNN 就能**统一运行时、零额外 .so**。
>
> 最后更新:2026-11-13。

---

## 1. 总体架构与数据流

```
写入期(learnMemory 落库时,每条记忆一次):
  content(+keywords) ──WordPiece──> ids ──MNN(bge encoder)──> 512维向量 ──存入 MemoryEntity.embedding

检索期(每次生成回复一次):
  当前对话 query ──(加检索指令前缀)WordPiece──> ids ──MNN encoder──> q向量
       │
       └─> 对候选记忆做 cosine(q, m.embedding) = relevance 分量
            └─> 进 MemoryRetrieval 三因子:score = recency + importance + relevance(各 min-max 归一)
```

关键:**记忆向量写入时算一次并存库;检索时只需给 query 算一次向量 + N 次点积**(O(N) 余弦,几百条无压力)。
与现有 importance/keywords 的「写入时产出、检索零额外调用」一致。

## 2. 模型与 tokenizer

- **模型**:`bge-small-zh-v1.5`,24M 参数,输出 **512 维**,最大 512 token。
  - 池化:**CLS pooling**(取第 0 个 token 的 hidden state)+ **L2 归一化**(bge 官方配置)。
  - 检索指令:**query 加前缀** `为这个句子生成表示以用于检索相关文章:`;记忆(passage)不加前缀。
- **格式转换**:`bge-small-zh-v1.5 ONNX → MNNConvert → .mnn`。✅ **已完成并核验(见 §12)。采用 fp16(47.5MB)**。
- **tokenizer**:bge 是 **BERT WordPiece**(中文 char 级 + `##` 续接),词表 `vocab.txt`(~110KB,2.1万词)。
  在 **Kotlin 侧**实现最小 WordPiece(加载 vocab,`[CLS]`/`[SEP]`,`[UNK]`,`##`),产出 `input_ids` + `attention_mask`。
  放 Kotlin 而非 native:易维护、与现有纯 Kotlin tokenize 风格一致、JNI 只传数字张量。

## 3. 原生层(复用 MNN,新增 encoder JNI)

现有 `libllmjni.so` 只封装了 MNN 的 **LLM(decoder)** API(`libllm`)。encoder 要用 MNN **核心推理 API**
(`libMNN`/`libMNN_Express`,不经 `libllm`)。在 `native/mnn-llm/` 扩展(或新增 `native/mnn-embed/`)三个 JNI:

```cpp
// 基于 MNN Interpreter/Session(CPU),复用已有的 libMNN.so / libMNN_Express.so
jlong   nativeEmbedLoad(String modelPath);                 // createFromFile + createSession(CPU,4线程)
jfloatArray nativeEmbed(jlong h, jintArray ids, jintArray mask);  // 见下
void    nativeEmbedRelease(jlong h);
```

`nativeEmbed` 流程:写 `input_ids` / `attention_mask` / `token_type_ids`(全 0)到输入张量 → `runSession`
→ 读 `last_hidden_state [1,T,512]` → **取 token0(CLS)** → **L2 归一化** → 返回 512 个 float。
(若模型图自带 pooler 输出则直接读。)

**构建**:重编 `libllmjni.so`(加这三个函数),复用现有 `libMNN/MNN_Express` .so → **零新增 .so 体积**。
需 MNN 头文件(按 `native/mnn-llm/BUILD.md` 的 MNN 源码树),NDK r27。Kotlin 侧新增:

```kotlin
object EmbeddingEngine {           // 与 LocalLlmEngine 并列,复用同一批 .so
    external fun nativeEmbedLoad(modelPath: String): Long
    external fun nativeEmbed(handle: Long, ids: IntArray, mask: IntArray): FloatArray
    external fun nativeEmbedRelease(handle: Long)
    // isReady / 单线程串行化 / 懒加载,同 LocalLlmEngine 风格
}
```

## 4. 数据模型变更(Room v3 → v4)

`MemoryEntity` 加一列存向量:

```kotlin
@ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray? = null  // 512×float32 = 2KB(可选 fp16=1KB)
@ColumnInfo(defaultValue = "0") val embeddingVersion: Int = 0                 // 模型/口径版本,变更触发重算
```

迁移 `MIGRATION_3_4`:`ALTER TABLE memory ADD COLUMN embedding BLOB` + `ADD COLUMN embeddingVersion INTEGER NOT NULL DEFAULT 0`。
旧行 embedding=null → 走**回填**(见 §6)。序列化:`FloatArray ⇄ ByteArray`(小端),DAO/Repository 层转换。

## 5. RelevanceScorer 重构(支持 query 向量化)

现有接口是为词法设计的(`score(item, queryTerms: Set<String>)`),embedding 需要「query 先整体编码一次」。
把 `MemoryRetrieval` 的 relevance 抽象改为**按整批打分**:

```kotlin
interface RelevanceScorer {
    /** 返回与 items 对齐的原始相关性(越大越相关);内部可对 query 只编码一次。 */
    fun scores(items: List<MemoryEntity>, query: String): DoubleArray
}
object LexicalRelevance : RelevanceScorer { /* 现逻辑:query 分词一次,逐条词面重叠 */ }
class EmbeddingRelevance(private val engine: EmbeddingEngine) : RelevanceScorer {
    override fun scores(items, query): DoubleArray {
        val q = engine.embed(QUERY_PREFIX + query)               // 512维,一次
        return items.map { m -> m.embeddingVec?.let { cosine(q, it) } ?: 0.0 }.toDoubleArray()
    }
}
```

`retrieve()` 改为调用 `relevance.scores(items, query)` 再 min-max 归一(recency/importance 不变)。
**缺向量的记忆 relevance=0**(不污染排序,仍靠 recency+importance 兜底)。三因子公式与权重不变。

## 6. 写入与回填

- **写入期**:`MemoryRepository.runLearning` 落库每条 addition/update 时,顺手 `engine.embed(content[+" "+keywords])`
  存入 `embedding`,`embeddingVersion = CURRENT`。与现有「写入时产出 importance/keywords」合并到同一步。
- **回填**:App 启动后台任务,若模型就绪且存在 `embedding==null` 或 `embeddingVersion<CURRENT` 的行 → 批量补算。
  手动编辑记忆文本时同样重算该行向量。
- **口径一致**:记忆向量用 content(+keywords)无前缀;query 向量加检索前缀。两者必须用同一模型/版本,
  否则 `embeddingVersion` 不符触发重算。

## 7. 模型分发

- 模型 ~24MB + vocab ~110KB。两种方案:
  - **(推荐)打包进 APK `assets/`**:始终可用、无下载 UX;首次启动 copy 到 `filesDir` 供 MNN 按路径加载。APK +~24MB。
  - 下载:复用 `LocalModelManager` 模式从 ModelScope 拉(省 APK 体积,但多一步 UX)。
- 选 assets:embedding 是记忆核心能力,值得常驻;24MB 可接受。

## 8. 回退与健壮性

- `RelevanceScorer` 可插拔已就绪:**模型缺失 / 非 arm64 / 加载失败 → 回退 `LexicalRelevance`**(现状,保证功能不挂)。
- 设置项:`memoryEmbeddingEnabled`(默认开,可一键回退词法),便于灰度与排障。
- 向量与词法可**混合**:`relevance = max(embedding_cos, 0.5×lexical)` 或加权,兜住 embedding 漏召回的词面强匹配
  (可作为二阶段实验,先纯 embedding)。

## 9. 成本与线程

- 写入期:每条记忆一次 encoder forward(短句,CPU ~数十 ms);低频、后台,无感。
- 检索期:每次生成多一次 query encode(~数十 ms)+ N 次 512 维点积(μs 级)。相对一次云/本地 LLM 调用可忽略。
- 线程:encoder 串行化到单独 dispatcher(同 `LocalLlmEngine`),与 LLM 推理互不阻塞;与 MNN LLM 可共存(各自 Interpreter)。
- 内存:bge-small int8 常驻 ~24–40MB,远小于 LLM。

## 10. 分阶段落地

1. **模型/工具**:转 bge-small-zh → `.mnn`(int8),导出 `vocab.txt`;离线核验转换后向量与 PC 版余弦一致(回归)。
2. **原生**:扩展 `native/mnn-llm` 加 encoder JNI,重编 `libllmjni.so`;Kotlin `EmbeddingEngine` + 单测 embed 一句话。
3. **数据层**:`MemoryEntity.embedding` + `MIGRATION_3_4` + BLOB 序列化;WordPiece tokenizer(Kotlin)+ 单测对齐 HF tokenizer。
4. **检索**:`RelevanceScorer.scores(items, query)` 重构 + `EmbeddingRelevance`;`memoryBlock(conv)` 走向量召回,缺失回退词法。
5. **写入/回填**:learn 时存向量;启动后台回填;编辑时重算。
6. **开关/灰度**:`memoryEmbeddingEnabled` + 回退路径;真机验证召回质量 vs 词法(用真实对话复测,对齐 benchmark 结论)。

## 11. 开放问题

- **动态 seq 轴**:导出的 ONNX/MNN 已用动态 seq 轴(batch+seq 可变),PC 上变长推理正常;端侧 JNI 需 `resizeTensor`+`resizeSession`。记忆/query 都短,也可固定 pad 到 64 简化。
- ~~int8 vs fp16~~ → **已定:fp16**(见 §12,int8 余弦仅 0.83)。
- ~~CLS vs mean~~ → **已定:CLS + L2 归一**(见 §12)。
- **向量 vs 词法混合**:纯 embedding 先上;是否加词法兜底(§8)留作二阶段 A/B。
- **backfill 触发时机**:启动即算 vs 懒算;大量旧记忆时的节流。

## 12. Phase 1 实测结果(2026-11-13)—— 模型转换与核验

**结论:bge-small-zh-v1.5 转 MNN fp16,端侧向量与 PC 版完全一致(cosine 1.0000)。** CLS 池化 + L2 归一。

| 变体 | 体积 | cos(MNN, sentence-transformers) |
|---|---|---|
| ONNX fp32(onnxruntime) | 95MB | 1.0000(基准,证明导出+CLS 正确) |
| MNN fp32 | 95MB | 1.0000 |
| **MNN fp16(采用)** | **47.5MB** | **1.0000** |
| MNN int8(--weightQuantBits 8) | 57MB | **0.83–0.89(弃用)** |

int8 对这个小模型的注意力权重太敏感、掉点严重,且体积反而比 fp16 大(词嵌入表未被有效量化)→ **用 fp16**。

**复现配方**:
```bash
pip install torch sentence-transformers transformers onnx onnxscript MNN
# 1) 从 HF 导出标准算子 ONNX(必须 eager 注意力,否则出 MultiHeadAttention/IsNaN,MNN 不支持)
python - <<'E'
import torch; from transformers import AutoModel, AutoTokenizer
m=AutoModel.from_pretrained("BAAI/bge-small-zh-v1.5", attn_implementation="eager").eval()
tok=AutoTokenizer.from_pretrained("BAAI/bge-small-zh-v1.5")
class W(torch.nn.Module):
    def __init__(s,n): super().__init__(); s.m=n
    def forward(s,i,a): return s.m(input_ids=i,attention_mask=a).last_hidden_state
e=tok("测试",return_tensors="pt")
dyn={"input_ids":{0:"b",1:"s"},"attention_mask":{0:"b",1:"s"},"last_hidden_state":{0:"b",1:"s"}}
torch.onnx.export(W(m).eval(),(e["input_ids"],e["attention_mask"]),"bge.onnx",
  input_names=["input_ids","attention_mask"],output_names=["last_hidden_state"],
  opset_version=14,dynamic_axes=dyn,dynamo=False)
E
# 2) 转 MNN fp16
mnnconvert -f ONNX --modelFile bge.onnx --MNNModel bge_fp16.mnn --bizCode bge --fp16
# vocab.txt 取自 BAAI/bge-small-zh-v1.5(WordPiece,21128 词)
```
产物:`bge_fp16.mnn`(47.5MB)+ `vocab.txt`。推理:输入 `input_ids`/`attention_mask`(int32),
读 `last_hidden_state[1,L,512]` → 取 token0(CLS)→ L2 归一;query 加前缀「为这个句子生成表示以用于检索相关文章:」。
