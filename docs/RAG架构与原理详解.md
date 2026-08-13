# RAG 架构与原理详解

> 本文说明 SmartKB 关键技术决策背后的原理、权衡与适用边界。

## 1. 为什么需要 RAG

大模型三大硬伤：**知识截止**（训练数据有日期）、**无私有知识**（你公司的文档它没见过）、**幻觉**（一本正经胡说）。

两条解决路线对比：

| | 微调 Fine-tuning | RAG 检索增强 |
|---|---|---|
| 原理 | 用私有数据继续训练模型权重 | 检索相关资料拼进 prompt，"开卷考试" |
| 知识更新 | 每次更新都要重训 | 改文档即时生效 |
| 可溯源 | 否（知识融进权重） | 是（能指出答案出处） |
| 成本 | GPU 训练成本高 | 只有推理与向量化成本 |
| 适用 | 改变模型风格/格式/领域语言 | 注入事实性知识 |

**结论**：注入事实知识首选 RAG；两者不互斥（先 RAG，风格不满足再微调）。

## 2. 索引链路：文档怎么变成可检索的向量

```
上传 → Tika解析 → 标题感知分块 → 批量 Embedding → 存库(embedding列) → 内存缓存
```

### 2.1 文档解析（DocumentParser）

- PDF/Word/PPT/HTML 走 **Apache Tika**（Spring AI 的 `TikaDocumentReader` 封装）；
- **Markdown/TXT 刻意不走 Tika**，按 UTF-8 原文直读——Tika 会把 `#` 标题符号处理掉，而我们的分块器需要标题结构。

### 2.2 智能分块（SmartTextSplitter）—— 检索质量的第一决定因素

**为什么不用现成的 TokenTextSplitter？** 它按固定 token 数硬切，会把句子拦腰截断、把标题与正文分家。RAG 的经验规律是：**垃圾分块 → 垃圾检索 → 垃圾回答**，分块是整条链路的地基。

本项目的三个策略：

1. **标题感知**：识别 Markdown 各级标题，维护标题栈生成 `titlePath`（如 `线程池 > 拒绝策略`）。两个收益：
   - 检索命中后能告诉用户"这段出自哪一节"（引用体验）；
   - 向量化时把 titlePath 拼进文本，给 embedding 补充章节上下文（`DocumentIndexer.embed`）。
2. **语义边界优先**：章节内先按段落切，超长段落再按句子切（`。！？；!?;` 断句），**绝不从句子中间断开**；仅当单句无标点超长（表格/代码）才退化为硬切。
3. **滑动重叠（overlap）**：相邻块共享尾部若干字符。防的是"答案恰好横跨两块边界被切碎"——两块都有边界附近内容，总有一块完整覆盖答案。

**chunk 大小怎么定**：
- 太小（<100 字）：语义不完整，召回后 LLM 拿到的信息不足；
- 太大（>1500 字）：一块里混多个主题，向量是"平均语义"反而模糊，且挤占 prompt 窗口、稀释注意力；
- 经验值 300~800 字符，**本项目按知识库可配置**（不同文档类型最优值不同：FAQ 短、论文长）。

### 2.3 Embedding 向量化

- 模型 `text-embedding-v3`（1024 维），把文本映射到语义空间：**语义相近 → 向量方向相近**；
- `EmbeddingService.embedBatch` 按 10 条一批调用向量模型，避免单次请求过大；
- **为什么异步**（DocumentIndexer + 独立线程池）：大文档解析+向量化分钟级，同步会占死 Tomcat 请求线程。上传接口只落 PENDING 记录即返回，前端轮询状态机 `PENDING → PARSING → INDEXED/FAILED`；
- **为什么两个线程池**（AsyncConfig）：索引是重 IO 大任务、问答要求低延迟，混用一个池会让批量上传把问答堵死——资源隔离（舱壁模式）。

## 3. 检索链路：五步流水线

```
问题 → ①查询改写 → ②a向量召回 + ②b关键词召回 → ③RRF融合 → ④LLM重排 → ⑤组装Prompt
```

### 3.1 多轮查询改写（QueryRewriter）

用户第二轮问"**那它的默认值是多少?**"——"它"指什么只有历史知道，直接拿这句去检索必然召回垃圾。解法：LLM 把"历史+新问题"压缩成自包含查询（如"线程池 corePoolSize 默认值"）再检索。这是多轮 RAG 的标配（LangChain 叫 condense question）。

细节：temperature=0（改写要稳定不要创造）；改写结果超过 300 字或调用失败 → 回退原问题。

### 3.2 为什么要混合检索（Hybrid Search）

向量检索的盲区是**精确匹配**：专有名词、型号、报错码（`ORA-00942`、`ThreadPoolExecutor`）在向量空间里与近义表述距离很近，但字面完全一致的原词不一定排最前。关键词检索（BM25 类）恰好相反：字面命中一定召回，但不懂语义（"电脑"搜不到"计算机"）。

**两路互补**：
- 向量路：`VectorEngine.search`，查询向量 vs 全库分块向量的余弦 Top-K，带相似度下限（minScore=0.35 过滤"最相似但其实不相关"的结果）；
- 关键词路：`KeywordRetriever`，简化 BM25 —— 中文按助词切分 + bigram 滑窗（生产换 IK/jieba 或 Elasticsearch），得分 = Σ 词频×log(1+词长)，除以 log(10+文长) 抑制长文档天然高分。

### 3.3 RRF 倒数排名融合（RrfFusion）

**问题**：两路得分量纲不同（余弦 0~1，关键词无上界），不能直接加权相加。

**RRF 只用名次**：`score(d) = Σ 1/(k + rank_i(d))`，k=60（原论文默认值）。

- 双路都命中的文档天然得分更高（两项相加）——这正是"多路共同认可"的语义；
- k 的作用是平滑头部差距：k 越大，第 1 名与第 10 名差距越小，越依赖多路共识；
- 优点：无需调权重、对量纲免疫、实现 10 行。Elasticsearch/Azure Search 的 hybrid 默认就是 RRF。

### 3.4 LLM 重排序（LlmReranker）

**召回和排序是两个问题**。向量召回是"近似语义匹配"，Top-K 里常混有"话题相关但答不了这个问题"的片段（问拒绝策略，召回了线程池简介）。重排让 LLM 逐条判断"这段能否回答该问题"打 0-10 分，低于阈值（默认 4）丢弃。

- 为什么值得多花一次调用：**prompt 里全是高质量上下文** → 显著减少答非所问与幻觉；上下文更短 → 生成的 token 成本反而可能降低；
- 生产优化：换专用重排模型（如 bge-reranker / gte-rerank），通常比通用 LLM 成本更低、延迟更短；
- 兜底：打分失败或全部被过滤时回退融合排序——宁可给模型一些上下文也不给空。

### 3.5 Prompt 组装与反幻觉（ChatService.buildSystemPrompt）

系统提示词三道防线：

1. **只依据片段作答，禁止编造**；
2. **引用标注**：要求句末标 `[n]`，前端把 `[n]` 渲染成可点击引用，用户能核对原文——"可验证"本身就是对幻觉的威慑；
3. **不足时明说**："知识库未找到相关信息"好过强行编——检索为空时 prompt 里明确告知模型这一事实。

配合 temperature=0.3（知识问答要忠实不要发挥）。

## 4. 向量存储：为什么敢自己写，何时换专业向量库

### 4.1 内置 LocalVectorEngine 的设计

- **存储**：向量 JSON 存 `doc_chunk.embedding` 列——与业务数据同库同事务，删文档删向量天然一致，无需分布式事务；
- **检索**：按知识库懒加载进内存（`ConcurrentHashMap<kbId, List<向量>>`），余弦相似度**暴力扫描** + 小顶堆取 Top-K；
- **失效**：文档增删 → `invalidate(kbId)` 清缓存，下次检索重新加载。

**复杂度分析**：O(n·d)，1 万分块 × 1024 维约为千万次浮点乘加，现代 CPU 可快速完成。中小知识库可先使用本地引擎，数据规模和可用性要求上升后再迁移专业向量库。

### 4.2 什么时候必须换专业向量库

- 数据量**百万级**：内存放不下 / 暴力扫描过慢，需要 **ANN 近似检索**；
- 需要高可用、分布式、多副本；
- 需要标量过滤 + 向量检索的复合查询下推。

**ANN 两类常见方案**：
- **HNSW**（分层可导航小世界图）：多层跳表式图结构，上层稀疏长边快速定位、底层稠密短边精确逼近；查询 O(log n)，召回率高，内存占用大——pgvector/Milvus/ES 都支持；
- **IVF**（倒排文件）：先 k-means 聚类，查询只扫最近的几个簇；内存友好，召回率略低，适合超大规模。

### 4.3 如何迁移 pgvector（面向接口的价值）

业务代码只依赖 `VectorEngine` 接口，迁移 = 新增一个实现类：

```java
@Component
@Profile("pgvector")
@RequiredArgsConstructor
public class PgVectorEngine implements VectorEngine {
    private final JdbcTemplate jdbc;

    @Override
    public List<ScoredHit> search(long kbId, float[] query, int topK, double minScore) {
        // <=> 是 pgvector 的余弦距离操作符, 相似度 = 1 - 距离
        return jdbc.query("""
                SELECT chunk_id, 1 - (embedding <=> ?::vector) AS score
                FROM kb_vector WHERE kb_id = ?
                ORDER BY embedding <=> ?::vector LIMIT ?
                """,
                (rs, i) -> new ScoredHit(rs.getLong(1), rs.getDouble(2)),
                toVectorLiteral(query), kbId, toVectorLiteral(query), topK);
    }
    @Override
    public void invalidate(long kbId) { /* 无缓存, 空实现 */ }
}
```

配套（生产 docker-compose 参考）：

```yaml
services:
  pgvector:
    image: pgvector/pgvector:pg16
    environment: { POSTGRES_PASSWORD: smartkb }
    ports: ["5432:5432"]
```

```sql
CREATE EXTENSION vector;
CREATE TABLE kb_vector (chunk_id BIGINT PRIMARY KEY, kb_id BIGINT, embedding vector(1024));
CREATE INDEX ON kb_vector USING hnsw (embedding vector_cosine_ops);
```

Spring AI 也提供 `spring-ai-starter-vector-store-pgvector` 一键接入（pom 中已留注释），自实现版本的价值是**说得清底层发生了什么**。

## 5. SSE 流式问答协议

```
POST /api/chat/stream          (fetch + ReadableStream 手动解析, 支持带 header/body)
  ← event:sources  {searchQuery, rewritten, chunks:[{n,docName,titlePath,content,四种得分}]}
  ← event:delta    {"t":"增量token"}     × N
  ← event:done     {promptTokens, completionTokens}
  ← event:error    {message}            (任一环节失败)
```

设计要点：

- **先推 sources 再生成**：用户第一时间看到"依据什么回答"，这是知识库产品的标准交互；
- **为什么不用 EventSource**：它只支持 GET 且不能带请求体；用 `fetch` 读流手动按 `\n\n` 切事件；
- **token 用量**：OpenAI 协议 `stream_options.include_usage`（Spring AI 的 `streamUsage(true)`），最后一个分块携带 usage；
- **登录态跨线程**：Sa-Token 的登录态在 ThreadLocal，SSE 流水线跑在 `chatExecutor` 线程池——必须在请求线程先 `checkOwned` + 取 userId 再提交任务（经典坑）；
- **断线兜底**：客户端断开后 `emitter.send` 抛异常静默吞掉，**消息落库不受影响**（完整回答仍会保存）。

## 6. 降级与容错设计（全链路）

| 故障点 | 降级行为 |
|---|---|
| 未配置 API Key | 索引跳过向量化（状态仍 INDEXED），检索走纯关键词，问答展示片段+提示 |
| Embedding 调用失败 | 文档标注"已降级为关键词检索"，不算索引失败 |
| 查询改写失败 | 用原问题检索 |
| 重排失败/全被过滤 | 回退 RRF 融合排序 |
| 生成中断 | 已生成的部分带"回答中断"标注落库 |
| 统计写入失败 | 吞异常，绝不影响主流程 |

**原则**：检索质量的每一级增强（向量、改写、重排）都是"锦上添花"，摘掉任何一级系统仍然可用。

## 7. 关键参数速查（application.yml `rag.*`）

| 参数 | 默认 | 调参方向 |
|---|---|---|
| vector-top-k / keyword-top-k | 8 / 8 | 召回不足 → 调大；噪声多 → 调小 |
| min-score | 0.35 | 常召回不相关内容 → 调高 |
| fused-top-k | 6 | 进入重排的候选数，影响重排成本 |
| final-top-k | 4 | 进 prompt 的片段数，影响生成成本与上下文噪声 |
| rrf-k | 60 | 一般不动（论文默认） |
| rerank-min-score | 4 | 答非所问多 → 调高；漏答多 → 调低 |
| history-window | 6 | 多轮上下文长度 vs token 成本 |

**下一步（P4 路线）**：构造问答对测试集，用召回率/MRR 量化每个参数的影响——"我调参是看指标的"比"我觉得效果好了"值钱十倍。
