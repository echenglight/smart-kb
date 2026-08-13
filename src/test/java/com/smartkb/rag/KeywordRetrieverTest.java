package com.smartkb.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 关键词提取单元测试(不触库, mapper 传 null) */
class KeywordRetrieverTest {

    private final KeywordRetriever retriever = new KeywordRetriever(null);

    @Test
    void 中英混合提取() {
        List<String> terms = retriever.extractTerms("ThreadPoolExecutor 的拒绝策略有哪些");
        assertTrue(terms.contains("ThreadPoolExecutor"));
        assertTrue(terms.contains("拒绝策略"));
    }

    @Test
    void 中文长串补充二字滑窗() {
        List<String> terms = retriever.extractTerms("垃圾回收器");
        assertTrue(terms.contains("垃圾回收器"));
        assertTrue(terms.contains("垃圾"));
        assertTrue(terms.contains("回收"));
    }

    @Test
    void 停用词与单字被过滤() {
        List<String> terms = retriever.extractTerms("的 了 是 a b");
        assertTrue(terms.isEmpty());
    }

    @Test
    void 空查询不报错() {
        assertTrue(retriever.extractTerms(null).isEmpty());
        assertTrue(retriever.extractTerms("").isEmpty());
    }
}
