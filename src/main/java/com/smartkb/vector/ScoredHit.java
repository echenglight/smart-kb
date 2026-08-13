package com.smartkb.vector;

/** 检索命中: 分块 id + 相似度得分 */
public record ScoredHit(long chunkId, double score) {
}
