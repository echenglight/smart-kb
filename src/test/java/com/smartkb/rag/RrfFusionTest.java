package com.smartkb.rag;

import com.smartkb.vector.ScoredHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RRF 融合单元测试 */
class RrfFusionTest {

    @Test
    void 双路命中的文档排名高于单路命中() {
        // 100 在两路都出现(排名都不是第一), 1 和 2 各只在一路第一
        List<ScoredHit> vector = List.of(hit(1), hit(100), hit(3));
        List<ScoredHit> keyword = List.of(hit(2), hit(100), hit(4));

        List<ScoredHit> fused = RrfFusion.fuse(List.of(vector, keyword), 60);
        assertEquals(100, fused.get(0).chunkId(), "双路命中应排第一");
        assertEquals(5, fused.size());
    }

    @Test
    void 得分按排名倒数计算() {
        List<ScoredHit> single = List.of(hit(1), hit(2));
        List<ScoredHit> fused = RrfFusion.fuse(List.of(single), 60);
        assertEquals(1.0 / 61, fused.get(0).score(), 1e-9);
        assertEquals(1.0 / 62, fused.get(1).score(), 1e-9);
    }

    @Test
    void 空输入返回空() {
        assertTrue(RrfFusion.fuse(List.of(List.of(), List.of()), 60).isEmpty());
    }

    private ScoredHit hit(long id) {
        return new ScoredHit(id, 0.5);
    }
}
