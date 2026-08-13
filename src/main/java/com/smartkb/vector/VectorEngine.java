package com.smartkb.vector;

import java.util.List;

/**
 * 向量检索引擎抽象
 *
 * 面向接口编程: 业务层(RAG 流水线)只依赖本接口, 更换底层向量数据库
 * (pgvector / Milvus / Elasticsearch kNN)时只需新增一个实现类。
 *
 * 默认实现 LocalVectorEngine: 向量持久化在 doc_chunk 表, 检索时加载进内存
 * 做余弦相似度暴力计算(brute-force)。万级分块毫秒出结果, 开发/演示零依赖;
 * 数据量到百万级再迁移 HNSW 近似检索的专业向量库(见 docs/RAG架构与原理详解.md)。
 */
public interface VectorEngine {

    /** 在指定知识库内做相似度 Top-K 检索, 过滤低于 minScore 的结果 */
    List<ScoredHit> search(long kbId, float[] queryVector, int topK, double minScore);

    /** 知识库内容变更(新增/删除文档)后使缓存失效 */
    void invalidate(long kbId);
}
