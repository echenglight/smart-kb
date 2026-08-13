package com.smartkb.chat;

import cn.dev33.satoken.stp.StpUtil;
import com.smartkb.ai.EmbeddingService;
import com.smartkb.chat.mapper.ChatMessageMapper;
import com.smartkb.common.JsonUtils;
import com.smartkb.rag.RagProperties;
import com.smartkb.rag.RagService;
import com.smartkb.rag.RetrievedChunk;
import com.smartkb.stats.AiCallLog;
import com.smartkb.stats.StatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 问答核心服务: 检索增强 + SSE 流式生成 + 引用溯源
 *
 * SSE 事件协议(前端按事件名分别处理):
 *   sources — 检索完成, 携带改写后检索词与引用片段(含全链路得分)
 *   delta   — 生成中的增量 token, JSON {"t": "..."}
 *   done    — 生成完毕, 携带 token 用量
 *   error   — 任一环节失败的可读错误
 *
 * 先推 sources 再流式生成是刻意设计: 用户第一时间看到"依据什么回答",
 * 生成再逐字补充 —— 这是知识库问答产品的标准交互(参考 Kimi/元宝的引用体验)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient.Builder chatClientBuilder;
    private final ConversationService conversationService;
    private final ChatMessageMapper messageMapper;
    private final RagService ragService;
    private final RagProperties props;
    private final StatsService statsService;
    private final EmbeddingService embeddingService;
    @Qualifier("chatExecutor")
    private final ThreadPoolTaskExecutor chatExecutor;

    @Value("${spring.ai.openai.chat.options.model:deepseek-v4-flash}")
    private String modelName;

    public SseEmitter stream(Long conversationId, String question) {
        // 归属校验与登录态读取必须在请求线程完成(ThreadLocal 不跨线程)
        ConversationEntity conv = conversationService.checkOwned(conversationId);
        long userId = StpUtil.getLoginIdAsLong();

        SseEmitter emitter = new SseEmitter(300_000L);
        chatExecutor.execute(() -> pipeline(emitter, conv, userId, question));
        return emitter;
    }

    private void pipeline(SseEmitter emitter, ConversationEntity conv, long userId, String question) {
        try {
            List<ChatMessageEntity> history =
                    conversationService.recentMessages(conv.getId(), props.getHistoryWindow());
            conversationService.touch(conv, question);
            saveMessage(conv.getId(), ChatMessageEntity.ROLE_USER, question, null, null, null);

            // ===== 检索阶段 =====
            RagService.RagResult rag = ragService.retrieve(userId, conv.getKbId(), question, history, true);
            List<Map<String, Object>> citations = buildCitations(rag.chunks());
            String citationsJson = JsonUtils.toJson(citations);
            send(emitter, "sources", JsonUtils.toJson(Map.of(
                    "searchQuery", rag.searchQuery(),
                    "rewritten", rag.rewritten(),
                    "chunks", citations)));

            if (!embeddingService.apiConfigured()) {
                send(emitter, "error", JsonUtils.toJson(Map.of("message",
                        "未配置 AI_API_KEY, 无法生成回答。上方已展示知识库中检索到的相关片段。")));
                emitter.complete();
                return;
            }

            // ===== 生成阶段 =====
            List<Message> messages = new ArrayList<>();
            for (ChatMessageEntity msg : history) {
                messages.add(ChatMessageEntity.ROLE_USER.equals(msg.getRole())
                        ? new UserMessage(msg.getContent())
                        : new AssistantMessage(msg.getContent()));
            }
            messages.add(new UserMessage(question));

            StringBuilder answer = new StringBuilder();
            AtomicReference<Usage> usageRef = new AtomicReference<>();
            long start = System.currentTimeMillis();

            chatClientBuilder.build().prompt()
                    .system(buildSystemPrompt(rag.chunks()))
                    .messages(messages)
                    .options(OpenAiChatOptions.builder()
                            .streamUsage(true)     // 流式最后一个分块携带 token 用量
                            .temperature(0.3)
                            .build())
                    .stream().chatResponse()
                    .doOnNext(resp -> captureUsage(resp, usageRef))
                    .map(this::extractText)
                    .filter(s -> !s.isEmpty())
                    .subscribe(
                            token -> {
                                answer.append(token);
                                send(emitter, "delta", JsonUtils.toJson(Map.of("t", token)));
                            },
                            error -> {
                                log.error("流式生成失败", error);
                                statsService.log(userId, AiCallLog.TYPE_CHAT, modelName, null,
                                        System.currentTimeMillis() - start, false, error.getMessage());
                                if (answer.length() > 0) {
                                    saveMessage(conv.getId(), ChatMessageEntity.ROLE_ASSISTANT,
                                            answer + "\n\n(回答中断: " + error.getMessage() + ")",
                                            citationsJson, rag.rewritten() ? rag.searchQuery() : null, null);
                                }
                                send(emitter, "error", JsonUtils.toJson(Map.of(
                                        "message", "生成失败: " + error.getMessage())));
                                emitter.complete();
                            },
                            () -> {
                                Usage usage = usageRef.get();
                                saveMessage(conv.getId(), ChatMessageEntity.ROLE_ASSISTANT,
                                        answer.toString(), citationsJson,
                                        rag.rewritten() ? rag.searchQuery() : null, usage);
                                statsService.log(userId, AiCallLog.TYPE_CHAT, modelName, usage,
                                        System.currentTimeMillis() - start, true, null);
                                send(emitter, "done", JsonUtils.toJson(Map.of(
                                        "promptTokens", usage == null || usage.getPromptTokens() == null
                                                ? 0 : usage.getPromptTokens(),
                                        "completionTokens", usage == null || usage.getCompletionTokens() == null
                                                ? 0 : usage.getCompletionTokens())));
                                emitter.complete();
                            });
        } catch (Exception e) {
            log.error("问答流水线异常", e);
            send(emitter, "error", JsonUtils.toJson(Map.of("message", "问答失败: " + e.getMessage())));
            emitter.complete();
        }
    }

    /**
     * 组装系统提示词: 编号片段 + 忠实性约束
     * "只依据片段作答 + 不足时明说" 是降低幻觉风险的第一道防线。
     */
    private String buildSystemPrompt(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder("""
                你是企业知识库问答助手。请严格依据下方知识库片段回答用户问题。
                规则:
                1. 只使用片段中的信息作答, 禁止编造片段之外的事实;
                2. 引用某个片段的内容时, 在对应句子末尾标注编号, 如 [1] 或 [1][3];
                3. 如果片段不足以回答问题, 明确说明"知识库中未找到相关信息", 可建议用户补充文档, 不要强行作答;
                4. 用中文回答, 使用 Markdown 排版, 结构清晰。

                """);
        if (chunks.isEmpty()) {
            sb.append("(本次检索未命中任何知识库片段, 请直接告知用户知识库中未找到相关信息。)");
        } else {
            sb.append("知识库片段:\n");
            for (int i = 0; i < chunks.size(); i++) {
                RetrievedChunk chunk = chunks.get(i);
                sb.append("\n[").append(i + 1).append("] 来源《").append(chunk.getDocName()).append("》");
                if (chunk.getTitlePath() != null && !chunk.getTitlePath().isEmpty()) {
                    sb.append(" 章节: ").append(chunk.getTitlePath());
                }
                sb.append('\n').append(chunk.getContent()).append('\n');
            }
        }
        return sb.toString();
    }

    private List<Map<String, Object>> buildCitations(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> citations = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("n", i + 1);
            item.put("chunkId", chunk.getChunkId());
            item.put("docName", chunk.getDocName());
            item.put("titlePath", chunk.getTitlePath());
            item.put("content", chunk.getContent());
            item.put("vectorScore", chunk.getVectorScore());
            item.put("keywordScore", chunk.getKeywordScore());
            item.put("rrfScore", chunk.getRrfScore());
            item.put("rerankScore", chunk.getRerankScore());
            citations.add(item);
        }
        return citations;
    }

    private void saveMessage(Long conversationId, String role, String content,
                             String citations, String rewrittenQuery, Usage usage) {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setCitations(citations);
        msg.setRewrittenQuery(rewrittenQuery);
        msg.setPromptTokens(usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens());
        msg.setCompletionTokens(usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);
    }

    private void captureUsage(ChatResponse resp, AtomicReference<Usage> ref) {
        if (resp != null && resp.getMetadata() != null) {
            Usage usage = resp.getMetadata().getUsage();
            if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                ref.set(usage);
            }
        }
    }

    private String extractText(ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return "";
        }
        String text = resp.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /** 发送 SSE 事件; 客户端断开时静默失败, 不影响消息落库 */
    private void send(SseEmitter emitter, String event, String jsonData) {
        try {
            emitter.send(SseEmitter.event().name(event).data(jsonData));
        } catch (Exception e) {
            log.debug("SSE 推送失败(客户端可能已断开): {}", e.getMessage());
        }
    }
}
