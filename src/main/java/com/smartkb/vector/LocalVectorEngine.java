package com.smartkb.vector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.smartkb.common.JsonUtils;
import com.smartkb.doc.DocChunkEntity;
import com.smartkb.doc.mapper.DocChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量向量引擎(默认实现)
 *
 * 存储: 向量以 JSON 存在 doc_chunk.embedding 列, 与业务数据同库同事务。
 * 检索: 按知识库懒加载进内存缓存, 余弦相似度全量扫描 + 小顶堆取 Top-K。
 *
 * 复杂度 O(n·d): n=分块数, d=1024 维。1 万分块 ≈ 千万次浮点乘加,
 * 现代 CPU 单核毫秒级 —— 中小知识库根本不需要专业向量库, 这是工程判断力。
 *
 * 设计依据:
 *  - 使用余弦相似度：归一化后只比较方向，不受向量长度影响；
 *  - 数据量达到百万级（内存不足或全量扫描过慢）、
 *    需要 HNSW/IVF 近似检索、需要分布式与高可用时。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalVectorEngine implements VectorEngine {

    private final DocChunkMapper chunkMapper;

    /** kbId -> 该库全部已向量化分块(懒加载) */
    private final Map<Long, List<IndexedVector>> cache = new ConcurrentHashMap<>();

    private record IndexedVector(long chunkId, float[] vector) {
    }

    @Override
    public List<ScoredHit> search(long kbId, float[] queryVector, int topK, double minScore) {
        List<IndexedVector> vectors = cache.computeIfAbsent(kbId, this::loadFromDb);
        if (vectors.isEmpty() || queryVector == null) {
            return List.of();
        }
        // 小顶堆维护 Top-K, 避免对全量结果排序
        PriorityQueue<ScoredHit> heap = new PriorityQueue<>(Comparator.comparingDouble(ScoredHit::score));
        for (IndexedVector iv : vectors) {
            double score = cosine(queryVector, iv.vector());
            if (score < minScore) {
                continue;
            }
            if (heap.size() < topK) {
                heap.offer(new ScoredHit(iv.chunkId(), score));
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.offer(new ScoredHit(iv.chunkId(), score));
            }
        }
        List<ScoredHit> hits = new ArrayList<>(heap);
        hits.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        return hits;
    }

    @Override
    public void invalidate(long kbId) {
        cache.remove(kbId);
    }

    private List<IndexedVector> loadFromDb(Long kbId) {
        long start = System.currentTimeMillis();
        List<DocChunkEntity> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<DocChunkEntity>()
                        .select(DocChunkEntity::getId, DocChunkEntity::getEmbedding)
                        .eq(DocChunkEntity::getKbId, kbId)
                        .isNotNull(DocChunkEntity::getEmbedding));
        List<IndexedVector> vectors = new ArrayList<>(chunks.size());
        for (DocChunkEntity chunk : chunks) {
            List<Float> values = JsonUtils.fromJson(chunk.getEmbedding(), new TypeReference<List<Float>>() {
            });
            float[] vec = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vec[i] = values.get(i);
            }
            vectors.add(new IndexedVector(chunk.getId(), vec));
        }
        log.info("知识库 {} 向量缓存加载完成: {} 条, 耗时 {}ms", kbId, vectors.size(),
                System.currentTimeMillis() - start);
        return vectors;
    }

    /** 余弦相似度: dot(a,b) / (|a|·|b|), 结果落在 [-1,1], 文本向量通常为 [0,1] */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
