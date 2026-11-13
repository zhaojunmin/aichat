# -*- coding: utf-8 -*-
"""
端侧 embedding 召回质量基准 —— 合成中文记忆语料。
对比:现有 lexical(复刻 Kotlin MemoryRetrieval) vs bge-small-zh vs gte-small-zh vs Qwen3-0.6B。
只测 relevance 排序质量(recency/importance 与静态语料无关,刻意排除)。
"""
import json, math, sys, time

# ----------------------------------------------------------------------------
# 语料:一个连贯人设(北京后端程序员,成都人)。记忆风格对标真机所见。
# ----------------------------------------------------------------------------
MEMORIES = [
    # 事实
    (1,  "事实", "在北京朝阳区上班"),
    (2,  "事实", "职业是后端程序员,主要用 Java 和 Go"),
    (3,  "事实", "老家是四川成都"),
    (4,  "事实", "1990 年出生"),
    (5,  "事实", "养了一只叫豆豆的英短猫"),
    (6,  "事实", "大学在武汉读的,计算机专业"),
    (7,  "事实", "有个妹妹在上海工作"),
    (8,  "事实", "开一辆蓝色的特斯拉 Model 3"),
    (9,  "事实", "工作日通勤单程要一个小时"),
    (10, "事实", "对花生过敏"),
    (11, "事实", "正在学吉他"),
    (12, "事实", "女朋友是同事,在一起两年了"),
    (13, "事实", "养猫之前养过一只仓鼠"),
    (14, "事实", "大学时在篮球校队打过球"),
    # 偏好
    (15, "偏好", "喜欢喝拿铁,不加糖"),
    (16, "偏好", "爱喝冰美式,夏天离不开"),
    (17, "偏好", "无辣不欢,最爱吃火锅和川菜"),
    (18, "偏好", "周末喜欢去郊外爬山"),
    (19, "偏好", "喜欢露营,有全套帐篷装备"),
    (20, "偏好", "爱看科幻片,最喜欢《星际穿越》"),
    (21, "偏好", "喜欢听周杰伦的歌"),
    (22, "偏好", "一般十一点前睡,不爱熬夜"),
    (23, "偏好", "买东西看重性价比,不追名牌"),
    (24, "偏好", "手机电脑都用苹果的产品"),
    (25, "偏好", "出去玩喜欢自驾,不爱跟团"),
    (26, "偏好", "怕冷,冬天不太爱出门"),
    (27, "偏好", "喜欢在安静的咖啡馆待着看书"),
    # 画像
    (28, "画像", "性格偏内向,社交多了需要独处充电"),
    (29, "画像", "做事认真,有点完美主义"),
    (30, "画像", "存钱意识强,每月固定拿钱出来理财"),
    (31, "画像", "不太会拒绝别人,容易心软"),
    (32, "画像", "最近在健身减脂,控制饮食"),
    # 风格
    (33, "风格", "说话简短直接,不爱绕弯子"),
    (34, "风格", "常用 哈哈 嗯嗯 这类语气词"),
    (35, "风格", "回复偏冷淡克制,很少用感叹号"),
    (36, "风格", "打字爱用省略号"),
    (37, "风格", "喜欢发表情包"),
    # 额外(制造干扰项与多相关)
    (38, "事实", "每天早上健身房撸铁一小时"),
    (39, "偏好", "喜欢吃甜的,尤其是蛋糕"),
    (40, "事实", "有驾照但平时很少自己开车"),
]

# query = 对方在聊天里说的话 / 当前对话片段;gold = 相关记忆 id。
QUERIES = [
    ("你在北京哪个区上班呀",            [1]),
    ("周末有空吗,一起去户外玩玩",       [18, 19]),
    ("中午一起吃火锅怎么样",            [17]),
    ("你能吃重口味的菜吗",             [17]),
    ("你家那只猫叫什么名字",            [5]),
    ("你家里养宠物吗",                [5, 13]),
    ("平时喝咖啡吗,一般点什么",         [15, 16]),
    ("晚上一起打游戏通宵啊",           [22]),
    ("你平时是做什么工作的",           [2]),
    ("你平时写代码用什么语言",          [2]),
    ("你老家是哪儿的",                [3]),
    ("你是南方人还是北方人",           [3]),
    ("最近有什么好看的电影推荐吗",       [20]),
    ("给我推荐个歌手呗",              [21]),
    ("这周末去爬山不",                [18]),
    ("感觉你这人不太爱说话",           [28, 33]),
    ("最近在健身吗",                 [32, 38]),
    ("你最近在控制体重?",             [32]),
    ("你平时开什么车",                [8]),
    ("出门你一般自己开还是坐地铁",       [8, 40]),
    ("你有兄弟姐妹吗",                [7]),
    ("你是哪一年出生的",              [4]),
    ("你对什么东西过敏吗",             [10]),
    ("冬天你爱出去浪吗",              [26]),
    ("你平时花钱大手大脚吗",           [23, 30]),
    ("你用安卓还是苹果手机",           [24]),
    ("你回消息怎么老是冷冰冰的",         [35, 33]),
    ("你会什么乐器吗",                [11]),
]

# ----------------------------------------------------------------------------
# Lexical:严格复刻 app/.../memory/MemoryRetrieval.kt 的 tokenize + LexicalRelevance
# ----------------------------------------------------------------------------
def is_cjk(ch):
    c = ord(ch)
    return 0x4E00 <= c <= 0x9FFF or 0x3400 <= c <= 0x4DBF or 0x3000 <= c <= 0x303F

def tokenize(text):
    out, cjk, ascii_ = set(), [], []
    def flush_cjk():
        s = "".join(cjk)
        if len(s) == 1: out.add(s)
        elif len(s) >= 2:
            for i in range(len(s) - 1): out.add(s[i:i+2])
        cjk.clear()
    def flush_ascii():
        if ascii_: out.add("".join(ascii_).lower())
        ascii_.clear()
    for ch in text:
        if is_cjk(ch): flush_ascii(); cjk.append(ch)
        elif ch.isalnum(): flush_cjk(); ascii_.append(ch)
        else: flush_cjk(); flush_ascii()
    flush_cjk(); flush_ascii()
    return out

def lexical_score(content, query_terms):
    ct = tokenize(content)
    hits = sum(1 for t in ct if t in query_terms)
    size = max(1, len(ct))
    return hits / math.sqrt(size)

def rank_lexical(mem_texts, query):
    qt = tokenize(query)
    scores = [lexical_score(t, qt) for t in mem_texts]
    return scores

# ----------------------------------------------------------------------------
# 指标
# ----------------------------------------------------------------------------
def ranking(scores):
    # 返回按分降序的 memory 下标(稳定:同分按原顺序)
    return sorted(range(len(scores)), key=lambda i: (-scores[i], i))

def evaluate(score_fn, ids, gold_list):
    """score_fn(qi)-> list[score] 对齐 ids。返回指标 dict。"""
    hit1 = r3 = r5 = mrr = 0.0
    n = len(gold_list)
    per_type = {"lexical": [0,0,0,0,0], "semantic": [0,0,0,0,0]}  # hit1,r3,r5,mrr,count
    for qi, gold in enumerate(gold_list):
        scores = score_fn(qi)
        order = ranking(scores)
        ranked_ids = [ids[i] for i in order]
        goldset = set(gold)
        # hit@1
        h1 = 1.0 if ranked_ids[0] in goldset else 0.0
        # recall@k = 命中的 gold 占比
        rec3 = len(goldset & set(ranked_ids[:3])) / len(goldset)
        rec5 = len(goldset & set(ranked_ids[:5])) / len(goldset)
        # MRR(第一个 gold 的倒数排名)
        rr = 0.0
        for rank, mid in enumerate(ranked_ids[:10], 1):
            if mid in goldset: rr = 1.0 / rank; break
        hit1 += h1; r3 += rec3; r5 += rec5; mrr += rr
        t = QTYPE[qi]
        per_type[t][0]+=h1; per_type[t][1]+=rec3; per_type[t][2]+=rec5; per_type[t][3]+=rr; per_type[t][4]+=1
    res = {"hit@1": hit1/n, "recall@3": r3/n, "recall@5": r5/n, "mrr@10": mrr/n, "n": n}
    res["by_type"] = {}
    for t,(a,b,c,d,cnt) in per_type.items():
        if cnt: res["by_type"][t] = {"hit@1":a/cnt,"recall@3":b/cnt,"recall@5":c/cnt,"mrr@10":d/cnt,"n":cnt}
    return res

# 自动分类:gold 记忆与 query 是否有共享 token → lexical-reachable,否则 semantic-only
MEM_BY_ID = {m[0]: m[2] for m in MEMORIES}
QTYPE = []
for q, gold in QUERIES:
    qt = tokenize(q)
    reachable = any(len(tokenize(MEM_BY_ID[g]) & qt) > 0 for g in gold)
    QTYPE.append("lexical" if reachable else "semantic")

# ----------------------------------------------------------------------------
# 运行
# ----------------------------------------------------------------------------
def main():
    ids = [m[0] for m in MEMORIES]
    texts = [m[2] for m in MEMORIES]
    queries = [q for q,_ in QUERIES]
    gold_list = [g for _,g in QUERIES]

    n_lex = sum(1 for t in QTYPE if t=="lexical")
    print(f"语料:{len(MEMORIES)} 条记忆,{len(QUERIES)} 条 query"
          f"(词面可达 {n_lex} / 纯语义 {len(QUERIES)-n_lex})\n")

    results = {}

    # --- lexical ---
    lex_cache = [rank_lexical(texts, q) for q in queries]
    results["lexical(现有)"] = evaluate(lambda qi: lex_cache[qi], ids, gold_list)

    # --- embedding 模型 ---
    try:
        from sentence_transformers import SentenceTransformer
        import numpy as np
    except Exception as e:
        print("!! 未装 sentence-transformers,仅输出 lexical 结果:", e)
        dump(results); return

    def enc(m, items, pname, prefix):
        """优先用 prompt_name(ST 预置指令),不支持则回退到手动前缀。"""
        if pname:
            try:
                return m.encode(items, prompt_name=pname, normalize_embeddings=True,
                                convert_to_numpy=True, batch_size=16, show_progress_bar=False)
            except Exception:
                pass
        pre = prefix or ""
        return m.encode([pre + t for t in items], normalize_embeddings=True,
                        convert_to_numpy=True, batch_size=16, show_progress_bar=False)

    # (名称, HF repo, query 前缀, query prompt_name, doc prompt_name)
    MODELS = [
        ("bge-small-zh-v1.5", "BAAI/bge-small-zh-v1.5", "为这个句子生成表示以用于检索相关文章:", None, None),
        ("gte-small-zh",      "thenlper/gte-small-zh",  "", None, None),
        ("all-MiniLM-L6-v2",  "sentence-transformers/all-MiniLM-L6-v2", "", None, None),
        ("Qwen3-Emb-0.6B",    "Qwen/Qwen3-Embedding-0.6B", "", "query", None),
        ("EmbeddingGemma-300m", "unsloth/embeddinggemma-300m", "", "query", "document"),
    ]
    for name, repo, q_prefix, q_prompt, doc_prompt in MODELS:
        try:
            t0 = time.time()
            print(f"加载 {name} ({repo}) ...", flush=True)
            m = SentenceTransformer(repo, device="cpu")
            doc_emb = enc(m, texts, doc_prompt, None)
            q_emb = enc(m, queries, q_prompt, q_prefix)
            sims = q_emb @ doc_emb.T  # cosine(已归一化)
            cache = [sims[qi].tolist() for qi in range(len(queries))]
            results[name] = evaluate(lambda qi, c=cache: c[qi], ids, gold_list)
            results[name]["_dim"] = int(doc_emb.shape[1])
            results[name]["_load_s"] = round(time.time()-t0, 1)
            print(f"  完成 dim={doc_emb.shape[1]} 用时 {time.time()-t0:.0f}s")
            del m
        except Exception as e:
            print(f"  !! {name} 失败:{e}")

    dump(results)

def dump(results):
    print("\n" + "="*72)
    hdr = f"{'方法':<22}{'hit@1':>8}{'recall@3':>10}{'recall@5':>10}{'MRR@10':>9}{'dim':>6}"
    print(hdr); print("-"*72)
    for name, r in results.items():
        dim = r.get("_dim", "-")
        print(f"{name:<22}{r['hit@1']:>8.3f}{r['recall@3']:>10.3f}{r['recall@5']:>10.3f}{r['mrr@10']:>9.3f}{str(dim):>6}")
    print("\n—— 按 query 类型拆分(recall@3)——")
    print(f"{'方法':<22}{'词面可达':>12}{'纯语义':>12}")
    print("-"*48)
    for name, r in results.items():
        bt = r.get("by_type", {})
        lx = bt.get("lexical",{}).get("recall@3", float('nan'))
        sm = bt.get("semantic",{}).get("recall@3", float('nan'))
        print(f"{name:<22}{lx:>12.3f}{sm:>12.3f}")
    # 存 JSON
    with open("/tmp/embed-bench/results.json","w") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    with open("/tmp/embed-bench/corpus.json","w") as f:
        json.dump({"memories":[{"id":i,"cat":c,"text":t} for i,c,t in MEMORIES],
                   "queries":[{"q":q,"gold":g,"type":QTYPE[k]} for k,(q,g) in enumerate(QUERIES)]},
                  f, ensure_ascii=False, indent=2)
    print("\n已写 /tmp/embed-bench/results.json 与 corpus.json")

if __name__ == "__main__":
    main()
