package com.smartkb.ai;

import com.smartkb.stats.AiCallLog;
import com.smartkb.stats.StatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 封装: 批量调用 + 用量记录 + 降级判断
 *
 * 向量模型单次请求按 10 条一批切分。
 * 未配置 API Key 或关闭向量功能时 available() 为 false, 索引流程自动降级为"仅关键词检索",
 * 保证项目克隆下来不配任何东西也能启动演示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private static final int BATCH_SIZE = 10;

    private final EmbeddingModel embeddingModel;
    private final StatsService statsService;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${smartkb.ai.embedding-enabled:true}")
    private boolean embeddingEnabled;

    @Value("${spring.ai.openai.embedding.options.model:text-embedding-v3}")
    private String modelName;

    /** 是否配置了真实 API Key */
    public boolean available() {
        return embeddingEnabled && apiConfigured();
    }

    /** 聊天模型是否配置了真实 API Key；与向量模型开关相互独立。 */
    public boolean apiConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("sk-placeholder");
    }

    /** 查询向量化(单条), 失败返回 null 由调用方降级 */
    public float[] embedQuery(Long userId, String text) {
        if (!available()) {
            return null;
        }
        long start = System.currentTimeMillis();
        try {
            EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));
            statsService.log(userId, AiCallLog.TYPE_EMBEDDING, modelName,
                    response.getMetadata() == null ? null : response.getMetadata().getUsage(),
                    System.currentTimeMillis() - start, true, "查询向量化");
            return response.getResults().get(0).getOutput();
        } catch (Exception e) {
            log.warn("查询向量化失败, 降级为纯关键词检索: {}", e.getMessage());
            statsService.log(userId, AiCallLog.TYPE_EMBEDDING, modelName, null,
                    System.currentTimeMillis() - start, false, e.getMessage());
            return null;
        }
    }

    /**
     * 批量向量化(文档索引用), 与输入顺序一一对应。
     * 任一批次失败直接抛出, 由索引流程统一降级处理。
     */
    public List<float[]> embedBatch(Long userId, List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        long start = System.currentTimeMillis();
        int totalTokens = 0;
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + BATCH_SIZE));
            EmbeddingResponse response = embeddingModel.embedForResponse(batch);
            response.getResults().forEach(r -> vectors.add(r.getOutput()));
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null
                    && response.getMetadata().getUsage().getTotalTokens() != null) {
                totalTokens += response.getMetadata().getUsage().getTotalTokens();
            }
        }
        // 聚合成一条流水, 避免一个大文档刷出几十条记录
        statsService.log(userId, AiCallLog.TYPE_EMBEDDING, modelName, null,
                System.currentTimeMillis() - start, true,
                "文档向量化 " + texts.size() + " 块, 约 " + totalTokens + " tokens");
        return vectors;
    }
}
