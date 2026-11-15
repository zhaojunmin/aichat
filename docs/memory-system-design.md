# 聊天记忆系统设计方案

> 面向本 App(Android 读屏 + 悬浮窗 AI 聊天助手)的长期记忆系统设计。
> 端侧约束:本地存储、隐私、低延迟、token 成本敏感。
>
> 本文结论来自一次多源、对抗式核验的深度调研(25 条声明验证,23 条确认,2 条否决),
> 每条结论标注了置信度与来源。落地建议结合了本仓库现有实现(`app/.../data/memory/`)。
>
> 最后更新:2026-11-13

---

## 0. TL;DR

证据一致指向 **「分层 + 抽取式 + RAG 召回」** 的记忆架构:

1. **短期/长期分层**,长期记忆走 `construction → management → retrieval → usage` 四阶段流水线。
2. **用 RAG 按需召回,而非全量历史注入** —— 这是降 token 成本和延迟的核心杠杆(成本/延迟维度证据确凿;质量维度不要过度解读)。
3. **混合存储**:key-value 存事实/偏好,向量存语义相似,图/链接存关系。
4. **抽取-更新增量管道**,支持 ADD/UPDATE/DELETE/NOOP 式去重与冲突消解。
5. **检索用三因子打分**(recency + importance + relevance),不要只用向量相似度。

**与现状最大的差距:** 当前 `memoryBlock()` 只按 `updatedAt` 取最近 N 条注入,**没有任何相关性召回**。补上「按当前对话召回相关记忆」是投入产出比最高的一步。

---

## 1. 现状盘点(本仓库)

| 环节 | 现有实现 | 位置 |
|---|---|---|
| 存储 | Room/SQLite 单表 `memory(id, category, content, updatedAt)`,category ∈ 画像/事实/偏好/风格 | `MemoryEntity.kt` |
| 记录 | 我发出的每条回复 append 到 `my_replies.txt`,bounded set 去重 | `MemoryRepository.recordReply` / `appendReply` |
| 抽取-更新 | pending 达阈值后调 `MemoryLearner.learn(existing, newReplies)` → 返回 additions + updates;只增量添加 + 覆盖 LLM 标记冲突的行,不自动删除 | `MemoryRepository.runLearning` |
| 注入 | 按 `updatedAt` 降序取最近 N 条,按 category 分组拼进 system prompt | `MemoryRepository.memoryBlock` (line 157-170) |
| 人工管理 | 逐条编辑/删除/清空 | `MemoryDao` + 设置界面 |

**已经做对的:** 分层雏形(原始 log → 蒸馏记忆)、抽取-更新增量管道、人工审计兜底、注入时按预算截断。

**主要缺口:**
- **注入无相关性召回**(只有 recency),与研究结论差距最大。
- 记忆单元信息维度单薄(无关键词/标签/embedding/链接),无法做语义召回。
- 冲突消解只有 ADD/UPDATE,无 DELETE/NOOP,也无显式去重判定。
- 无记忆巩固(reflection)。

---

## 2. 架构选型

### 2.1 顶层骨架:分层 + 四阶段流水线 `[高 · 3-0]`

长期记忆按 `construction → management → retrieval → usage` 组织:

- **construction**(抽取)= 从多轮对话抽取/提炼 → 对应现有 `MemoryLearner.learn`
- **management**(更新)= 去重/合并/冲突消解 → 现有部分覆盖
- **retrieval**(召回)= SQL / 模糊 / 相似度 → **现有缺失**
- **usage**(注入)= 作为上下文注入 → 现有 `memoryBlock`(但跳过了 retrieval)

> 来源:*A Survey on Memory in LLM Agents*, arXiv:2504.15965(primary)

### 2.2 RAG 按需召回 vs 全量注入 `[高 · 3-0]`

用 RAG 召回相关记忆,而不是把全部历史塞进上下文。这是降成本/延迟的核心杠杆:

- Mem0 相对全量上下文:**p95 延迟降约 91%**(1.44s vs 17.1s)、**token 成本降 >90%**。

> ⚠️ **限定**:该优势严格限定在**成本/效率**维度。**答案质量**维度学界有相反证据(长上下文有时更准;Mem0 自己测试里全量注入质量反而最高 72.9%)。端侧是用小幅质量换大幅成本/延迟 —— 取舍值得,但别当成「RAG 一律更优」。
>
> 来源:arXiv:2504.15965;Mem0, journals.sagepub.com/doi/10.3233/FAIA251160

### 2.3 混合存储 `[高 · 3-0]`

| 存储 | 存什么 | 端侧实现 | 检索方式 |
|---|---|---|---|
| key-value | 用户事实、偏好 | SQLite 表(≈现有 `memory` 表) | SQL 精确查 |
| 向量 | 语义/上下文相似 | sqlite-vec / ObjectBox 本地索引 | top-k 余弦 |
| 图/链接 | 实体与记忆间关系 | 轻量链接表 | 图遍历 |

存储格式决定检索方式。端侧最小可行 = SQLite(已具备) + 本地向量索引(待加)。

> 来源:arXiv:2504.15965;两轴分类法 arXiv:2505.00675

### 2.4 代表性系统定位

| 系统 | 借鉴点 |
|---|---|
| Generative Agents(斯坦福小镇) | 检索三因子打分 + reflection 巩固 |
| Mem0 | 抽取-更新 / ADD·UPDATE·DELETE·NOOP 冲突消解 + 成本数据 |
| A-MEM | 记忆单元 note schema + 写入期动态链接与记忆演化 |
| MemGPT/Letta | 分层内存管理思路(对端侧偏重) |
| TriMem | 多粒度记忆(原始片段 + 原子事实 + 合成画像) |

---

## 3. 记忆抽取与更新

### 3.1 记忆单元:结构化 note `[高 · 3-0]`

A-MEM schema,每条记忆 =
`{原始内容, 时间戳, LLM 关键词, LLM 标签, LLM 上下文描述, embedding, 链接列表}`。
embedding 由 `原始内容 + 关键词 + 标签 + 描述` 拼接后编码。

好处:可按标签过滤、按关键词召回,维度远比裸 embedding 丰富。
**端侧只需写入时一次 LLM 调用**生成这些属性。

> 对标现状:现有 `MemoryEntity` 只有 `category/content/updatedAt`。建议扩展列:
> `keywords`、`tags`、`embedding`(BLOB)、`importance`(写入时预存,见 4.2)、`links`。
>
> 来源:*A-MEM*, arXiv:2502.12110

### 3.2 抽取-更新增量管道 `[高 · 3-0]`

- **Mem0 两阶段**:extraction(识别事实)→ update(对已有记忆施加 **ADD / UPDATE / DELETE / NOOP**)。这就是去重 + 冲突消解。
- **A-MEM 记忆演化**:写入新记忆时动态链接相似历史记忆,并可**触发对旧记忆上下文/关键词/标签的更新** —— 记忆网络持续自我精炼,而非 append-only。

> 对标现状:现有 `runLearning` 已有 extraction + ADD/UPDATE,**缺 DELETE/NOOP 和显式去重**。
> 升级方向:让 `MemoryLearner.learn` 的 prompt 显式对每条候选输出 ADD/UPDATE/DELETE/NOOP 决策。
>
> ⚠️ **风险**:ADD/UPDATE/DELETE 都由 LLM 判定;端侧若用小模型,误删/错更新风险更高。
> **保留可审计编辑日志** —— 本 App 已有的「逐条编辑/删除」界面正好做人工兜底。
>
> 来源:Mem0(FAIA251160)、A-MEM(2502.12110)

---

## 4. 记忆检索与注入

### 4.1 三因子打分(本方案最关键的新增)`[高 · 3-0]`

Generative Agents 经典公式:

```
score = α_recency · recency + α_importance · importance + α_relevance · relevance
```

- 三项 min-max 归一到 [0,1],α 默认都 = 1(可调)
- **recency** = 指数衰减(衰减因子 0.995)
- **importance** = LLM 给的 poignancy 1-10(不是启发式)
- **relevance** = query(当前对话)与记忆 embedding 的余弦相似度

检索取 top-k 后再注入。A-MEM 的 top-k 余弦召回 `[3-0]` 就是 relevance 分量的具体实现。

> 对标现状:`memoryBlock()` 目前**只有 recency**(`sortedByDescending updatedAt`)。
> 这是最该补的一环:加入 relevance(需要 embedding)+ importance(写入时预存)。
>
> **端侧优化**:importance 在**写入时**顺带产出并存库,检索时零额外 LLM 调用。
>
> 来源:*Generative Agents*, arXiv:2304.03442 / ACM 10.1145/3586183.3606763

### 4.2 记忆巩固:reflection `[高 · 3-0]`

LLM 周期性把零散观察合成为更抽象的高层记忆(树状结构)。消融实验证明去掉 reflection 行为质量可测量下降(TrueSkill 29.89 → 26.88)。

> 端侧落地:做成**空闲/充电时的批处理任务**,不占交互路径延迟。

### 4.3 多粒度记忆:别只存原子事实 `[中 · 2-1 / 3-0]`

纯原子事实范式会丢失细粒度细节、难支持跨事实深度推理。TriMem 主张三粒度共存:

1. **原始对话片段**(带源标识,保真)
2. **抽取的原子事实**(高效检索)—— 现有系统就在这一层
3. **合成画像**(深度推理)—— 现有的「画像」category 已有雏形

端侧按**上下文预算分层注入**:默认注入原子事实,需要时再回查原始片段。

> ⚠️ 该批判是作者为自家方法立的靶子(self-serving),原文用「Most」有对冲。作为**方向**借鉴,非定论。
>
> 来源:TriMem, arXiv:2605.19952

### 4.4 注入格式:缺强证据,需自测 ⚠️

关于「注入格式/排序与召回同等重要」的声明在核验中**被否决**(检索-生成差距 75%→32% 的数据,1-2 未通过)。
所以注入 prompt 的具体格式(模板、排序、分隔符)**目前缺乏强证据,需要自己做 A/B 实验**。

---

## 5. 工程落地与成本(端侧)

- **外部记忆 vs 全量注入**:成本/延迟方向性优势确凿 `[中,数字厂商自报]`。
- 外部抽取式记忆可**超越模型内置记忆**(Mem0 vs OpenAI 内置 +26%),但**打不过全量上下文的质量** —— 拿质量换成本。

> ⚠️ 所有端侧落地细节(SQLite + 本地向量库、写入期生成属性、importance 预计算)都是**基于证据的工程推断**,
> 没有来源直接在 Android 上实测过。落地需自行验证。

---

## 6. 落地路线图(对标现有代码)

### Phase 1 — 补相关性召回(最高 ROI)✅ 已实现
目标:注入从「最近 N 条」升级为「与当前对话最相关的 N 条」。

决策:relevance 分量采用**纯本地词法匹配**(零新依赖、全端侧、零额外延迟/成本),
`RelevanceScorer` 接口可插拔,向量/embedding 以后可无痛替换(见 `MemoryRetrieval.kt`)。

> 📊 **后续评估(2026-11-13)**:做了端侧 embedding 选型 + 召回质量基准(见
> [`embedding-benchmark/`](embedding-benchmark/README.md))。结论:纯语义 query 上 lexical 几乎失效
> (recall@3 0.133),好的 embedding 把整体 hit@1 从 0.43 拉到 0.86–0.89。**最终决策:选 bge-small-zh-v1.5
> (int8,复用 MNN,零额外 .so)**作为 `RelevanceScorer` 的 embedding 实现(质量/体积双优,且本地 LLM A/B
> 证实这台设备 LiteRT 无优势、与 LLM 统一在 MNN)。集成方案见
> [embedding-integration-design.md](embedding-integration-design.md)。

1. ✅ `MemoryEntity` 加列:`keywords`(String)、`importance`(Int)。(embedding BLOB 留给向量升级,本阶段不加。)
2. ✅ Room 迁移 `v1→v2`(`MIGRATION_1_2`,旧行给安全默认值)。
3. ✅ `MemoryLearner.learn` prompt 升级:一次 LLM 调用同时产出 content + keywords + importance(1-10),写入时持久化。
4. ✅ `MemoryRetrieval.retrieve(...)`:recency(按日指数衰减)+ importance + 本地词法 relevance,三项 min-max 归一后加权取 top-k。
5. ✅ `memoryBlock(conv)` 接收当前对话,调用 `retrieve`;`conv` 为空时回退 recency。调用方 `FloatingBubbleService` 已传入 `conv`。

### Phase 2 — 冲突消解与演化(部分已实现)
6. ✅ `MemoryLearner.learn` prompt 升级为显式 ADD/UPDATE/**DELETE**/NOOP(NOOP 隐式=不提及);DELETE 走**软删除**(`deletedAt`/`deletedBy`)+ 回收站,接入管理界面人工兜底(恢复 / 彻底删除)。迁移 `v2→v3`。
7. ⏸ 写入期动态链接相似历史记忆(A-MEM 演化)—— 暂不做。学习器的 UPDATE 已覆盖「新信息修正旧记忆」的实质;显式链接图对本 App 偏重且证据为 self-serving,留作后续。
8. ⏸ 多粒度回查(TriMem)—— 暂不做。原始片段已在 `my_replies.txt`;按预算回查缺强证据(见 §4.4),留作后续实验。

> ⚠️ **已知限制**:传给学习器的 `existing` 是 active 记忆(不含回收站),用户手动删除的条目理论上可能被重新 ADD(resurrection)。影响较小(可再删),如需彻底杜绝,后续可给学习器传 tombstone 列表。

### Phase 3 — 巩固与打磨
9. 空闲/充电时跑 reflection 批处理,生成高层「画像」记忆。
10. A/B 实验注入格式与三粒度预算分配(无现成答案)。

---

## 7. 开放问题(研究未覆盖,需自行决策)

1. **端侧 LLM 调用成本分配**:写入期抽取/打标签/演化多大比例放本地小模型、多大比例延迟批处理?无现成端侧成本模型。
2. **注入格式与预算分配**:三粒度最优注入比例和格式 —— 无强证据,需自测。
3. **隐私与端上加密**:记忆含用户事实和原始聊天片段,本地加密 / 访问控制 / 遗忘权(right-to-forget)如何做,对检索延迟影响多大?来源只泛泛提隐私。
4. **冲突消解正确性**:端侧小模型做 DELETE 的误操作如何控制?建议走软删除 + 可审计日志 + 人工兜底。

---

## 8. 方法学提醒

- 领域演进极快(来源集中 2023-2026),Mem0/A-MEM/TriMem 具体数字可能随版本变化。
- Mem0 性能数字为**厂商自报 + LLM-as-a-Judge**;独立方(getzep 等)对其**竞品对比**方法学有质疑,但对「外部记忆 vs 全量上下文」方向性结论冲击较小。当方向参考,别当精确承诺。
- 两条声明被核验否决,**不要据此主张**:① 检索-生成差距 75%/32%(1-2);② A-MEM 在 LoCoMo 上 F1 翻倍且更省 token(0-3)。

---

## 附录:主要来源

| 来源 | 类型 | 作用 |
|---|---|---|
| arXiv:2504.15965 | primary | 记忆综述(四阶段、三维分类、存储形态) |
| arXiv:2502.12110 | primary | A-MEM(note schema、记忆演化、召回) |
| journals.sagepub.com/doi/10.3233/FAIA251160 | primary | Mem0(抽取-更新、冲突消解、成本数据) |
| arXiv:2304.03442 / ACM 10.1145/3586183.3606763 | primary | Generative Agents(三因子打分、reflection) |
| arXiv:2605.19952 | primary | TriMem(多粒度记忆) |
| arXiv:2505.00675 | primary | 两轴记忆分类法 |

调研规模:5 个检索角度 · 24 个来源抓取 · 118 条声明 · 25 条对抗式验证(确认 23 / 否决 2)。
