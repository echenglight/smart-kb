package com.smartkb.doc.split;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 分块器单元测试: 标题感知 / 尺寸控制 / 滑动重叠 / 边界情况 */
class SmartTextSplitterTest {

    private final SmartTextSplitter splitter = new SmartTextSplitter();

    @Test
    void 空文本返回空列表() {
        assertTrue(splitter.split(null, 500, 80).isEmpty());
        assertTrue(splitter.split("   \n\n  ", 500, 80).isEmpty());
    }

    @Test
    void markdown标题生成titlePath链路() {
        String md = """
                # 线程池
                ## 核心参数
                corePoolSize 是核心线程数, 即使空闲也不会被回收。这是一段足够长的正文内容。
                ## 拒绝策略
                AbortPolicy 直接抛出异常, 是默认的拒绝策略行为。
                """;
        List<TextChunk> chunks = splitter.split(md, 500, 50);
        assertEquals(2, chunks.size());
        assertEquals("线程池 > 核心参数", chunks.get(0).titlePath());
        assertEquals("线程池 > 拒绝策略", chunks.get(1).titlePath());
        assertTrue(chunks.get(0).content().contains("corePoolSize"));
    }

    @Test
    void 高级标题出现时清空更深层级() {
        String md = """
                # 甲
                ## 甲一
                甲一的内容, 长度需要超过最小分块阈值才会保留下来。
                # 乙
                乙的内容直接挂在一级标题下面, 同样需要足够的长度。
                """;
        List<TextChunk> chunks = splitter.split(md, 500, 0);
        assertEquals("甲 > 甲一", chunks.get(0).titlePath());
        assertEquals("乙", chunks.get(1).titlePath());     // "甲一" 不应残留
    }

    @Test
    void 长文本被切成多块且不超过上限太多() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("这是第").append(i).append("句话, 用来撑起足够的长度以触发分块逻辑。");
        }
        int chunkSize = 200;
        List<TextChunk> chunks = splitter.split(sb.toString(), chunkSize, 40);
        assertTrue(chunks.size() > 3, "长文本应被切成多块");
        for (TextChunk chunk : chunks) {
            // 允许一句话的溢出余量, 但不应该出现 2 倍超限
            assertTrue(chunk.content().length() <= chunkSize * 2,
                    "块过大: " + chunk.content().length());
        }
    }

    @Test
    void 相邻块存在重叠内容() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("第").append(i).append("句用于验证重叠逻辑的句子。");
        }
        List<TextChunk> chunks = splitter.split(sb.toString(), 150, 40);
        assertTrue(chunks.size() >= 2);
        String firstTail = chunks.get(0).content()
                .substring(Math.max(0, chunks.get(0).content().length() - 20));
        assertTrue(chunks.get(1).content().contains(firstTail.substring(firstTail.length() / 2)),
                "第二块应包含第一块的尾部内容(滑动重叠)");
    }

    @Test
    void 无标点超长文本硬切不丢失() {
        String text = "a".repeat(1200);
        List<TextChunk> chunks = splitter.split(text, 400, 0);
        int total = chunks.stream().mapToInt(c -> c.content().length()).sum();
        assertTrue(total >= 1200, "硬切不应丢内容");
    }
}
