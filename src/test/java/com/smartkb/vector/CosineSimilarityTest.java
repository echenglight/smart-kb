package com.smartkb.vector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 余弦相似度单元测试 */
class CosineSimilarityTest {

    @Test
    void 相同向量相似度为1() {
        float[] v = {0.3f, 0.5f, 0.8f};
        assertEquals(1.0, LocalVectorEngine.cosine(v, v), 1e-6);
    }

    @Test
    void 正交向量相似度为0() {
        assertEquals(0.0, LocalVectorEngine.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-6);
    }

    @Test
    void 反向向量相似度为负1() {
        assertEquals(-1.0, LocalVectorEngine.cosine(new float[]{1, 2}, new float[]{-1, -2}), 1e-6);
    }

    @Test
    void 零向量或维度不一致返回0() {
        assertEquals(0.0, LocalVectorEngine.cosine(new float[]{0, 0}, new float[]{1, 2}), 1e-6);
        assertEquals(0.0, LocalVectorEngine.cosine(new float[]{1}, new float[]{1, 2}), 1e-6);
    }
}
