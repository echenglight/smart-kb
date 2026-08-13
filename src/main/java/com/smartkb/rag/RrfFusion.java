package com.smartkb.rag;

import com.smartkb.vector.ScoredHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF(Reciprocal Rank Fusion) 倒数排名融合
 *
 * 多路召回的得分量纲不同(余弦相似度 0~1, 关键词 tf 得分无上界), 不能直接相加。
 * RRF 只用"名次"融合: score(d) = Σ 1/(k + rank_i(d)), rank 从 1 开始。
 *
 * k(默认 60)的作用: 平滑头部差距 —— k 越大, 第 1 名与第 10 名的差距越小,
 * 更依赖"多路共同认可"; k 越小越偏向各路的头部结果。
 * 双路都命中的文档天然得分更高, 这正是混合检索想要的效果。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /** 融合多路排名, 返回按 RRF 得分降序的列表 */
    public static List<ScoredHit> fuse(List<List<ScoredHit>> rankings, int k) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (List<ScoredHit> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                scores.merge(ranking.get(rank).chunkId(), 1.0 / (k + rank + 1), Double::sum);
            }
        }
        List<ScoredHit> fused = new ArrayList<>(scores.size());
        scores.forEach((id, score) -> fused.add(new ScoredHit(id, score)));
        fused.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        return fused;
    }
}
