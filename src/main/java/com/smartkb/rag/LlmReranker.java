package com.smartkb.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.smartkb.common.JsonUtils;
import com.smartkb.stats.AiCallLog;
import com.smartkb.stats.StatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 重排序(Rerank)
 *
 * 为什么召回后还要重排: 向量检索是"近似语义匹配", 召回的 Top-K 里常混有
 * "话题相关但答不了这个问题"的片段。重排让 LLM 逐条判断"这段能不能回答
 * 该问题"打 0-10 分, 低分丢弃 —— 用一次小请求换 prompt 里全是高质量上下文,
 * 显著减少答非所问与幻觉。
 *
 * 生产可换专用重排模型(如 bge-reranker、gte-rerank), 比通用 LLM 便宜且快;
 * 本项目用 LLM 打分演示原理, 失败时兜底返回原始顺序。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmReranker {

    private static final String SYSTEM_PROMPT = """
            你是检索结果相关性评审员。给定一个问题和若干编号的文本片段,
            为每个片段打分(0-10): 10 分=直接包含答案, 5 分=部分相关, 0 分=无关。
            只输出 JSON 数组, 不要任何其他文字, 格式:
            [{"index":0,"score":8},{"index":1,"score":3}]""";

    private final ChatClient.Builder chatClientBuilder;
    private final StatsService statsService;

    @Value("${spring.ai.openai.chat.options.model:deepseek-v4-flash}")
    private String modelName;

    /** 打分并按得分降序返回, 过滤低于 minScore 的片段; 失败时原样返回 */
    public List<RetrievedChunk> rerank(Long userId, String query, List<RetrievedChunk> chunks, int minScore) {
        if (chunks.size() <= 1) {
            return chunks;
        }
        long start = System.currentTimeMillis();
        try {
            StringBuilder user = new StringBuilder("问题: ").append(query).append("\n\n");
            for (int i = 0; i < chunks.size(); i++) {
                user.append("[片段").append(i).append("] ")
                        .append(truncate(chunks.get(i).getContent(), 400)).append("\n\n");
            }

            ChatResponse response = chatClientBuilder.build().prompt()
                    .system(SYSTEM_PROMPT)
                    .user(user.toString())
                    .options(OpenAiChatOptions.builder().temperature(0.0).build())
                    .call().chatResponse();
            String text = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    ? null : response.getResult().getOutput().getText();
            statsService.log(userId, AiCallLog.TYPE_RERANK, modelName,
                    response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage(),
                    System.currentTimeMillis() - start, true, null);
            if (text == null) {
                return chunks;
            }

            List<Map<String, Integer>> scores = JsonUtils.fromJson(stripFences(text),
                    new TypeReference<List<Map<String, Integer>>>() {
                    });
            for (Map<String, Integer> item : scores) {
                Integer index = item.get("index");
                Integer score = item.get("score");
                if (index != null && score != null && index >= 0 && index < chunks.size()) {
                    chunks.get(index).setRerankScore((double) score);
                }
            }
            List<RetrievedChunk> kept = new ArrayList<>(chunks.stream()
                    .filter(c -> c.getRerankScore() == null || c.getRerankScore() >= minScore)
                    .toList());
            kept.sort((a, b) -> Double.compare(
                    b.getRerankScore() == null ? -1 : b.getRerankScore(),
                    a.getRerankScore() == null ? -1 : a.getRerankScore()));
            // 全部被过滤时退回原始顺序, 宁可给模型一些上下文也不给空
            return kept.isEmpty() ? chunks : kept;
        } catch (Exception e) {
            log.warn("重排失败, 使用融合排序: {}", e.getMessage());
            statsService.log(userId, AiCallLog.TYPE_RERANK, modelName, null,
                    System.currentTimeMillis() - start, false, e.getMessage());
            return chunks;
        }
    }

    /** 剥掉模型偶尔包裹的 ```json ... ``` 围栏 */
    private String stripFences(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            s = s.substring(s.indexOf('\n') + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) {
                s = s.substring(0, end);
            }
        }
        return s.strip();
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
