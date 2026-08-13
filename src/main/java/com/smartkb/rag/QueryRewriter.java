package com.smartkb.rag;

import com.smartkb.chat.ChatMessageEntity;
import com.smartkb.stats.AiCallLog;
import com.smartkb.stats.StatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多轮查询改写(Query Rewriting / Condensation)
 *
 * 问题场景: 用户第二轮问"那它的默认值是多少?" —— "它"指代什么只有结合
 * 历史才知道, 直接拿这句话去做向量检索必然召回垃圾。
 * 解法: 让 LLM 把"历史 + 最新问题"压缩改写成一个自包含的检索查询,
 * 如 "线程池 corePoolSize 的默认值是多少"。这是多轮 RAG 的标配前置步骤。
 *
 * 失败兜底: 改写调用异常时直接用原问题检索, 不阻断主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRewriter {

    private static final String SYSTEM_PROMPT = """
            你是检索查询改写器。根据对话历史, 把用户最新的问题改写成一个独立、完整、
            包含所有指代信息的中文检索查询, 用于知识库搜索。
            要求: 只输出改写后的查询本身, 不要任何解释、引号或前缀;
            如果最新问题本身已经完整独立, 原样输出即可。""";

    private final ChatClient.Builder chatClientBuilder;
    private final StatsService statsService;

    @Value("${spring.ai.openai.chat.options.model:deepseek-v4-flash}")
    private String modelName;

    public String rewrite(Long userId, List<ChatMessageEntity> history, String question) {
        if (history == null || history.isEmpty()) {
            return question;
        }
        long start = System.currentTimeMillis();
        try {
            StringBuilder context = new StringBuilder("对话历史:\n");
            for (ChatMessageEntity msg : history) {
                String role = ChatMessageEntity.ROLE_USER.equals(msg.getRole()) ? "用户" : "助手";
                context.append(role).append(": ").append(truncate(msg.getContent(), 200)).append('\n');
            }
            context.append("\n用户最新问题: ").append(question);

            ChatResponse response = chatClientBuilder.build().prompt()
                    .system(SYSTEM_PROMPT)
                    .user(context.toString())
                    .options(OpenAiChatOptions.builder().temperature(0.0).build())
                    .call().chatResponse();

            String rewritten = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    ? null : response.getResult().getOutput().getText();
            statsService.log(userId, AiCallLog.TYPE_REWRITE, modelName,
                    response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage(),
                    System.currentTimeMillis() - start, true, null);

            if (rewritten == null || rewritten.isBlank() || rewritten.length() > 300) {
                return question;
            }
            log.debug("查询改写: [{}] -> [{}]", question, rewritten.strip());
            return rewritten.strip();
        } catch (Exception e) {
            log.warn("查询改写失败, 使用原始问题检索: {}", e.getMessage());
            statsService.log(userId, AiCallLog.TYPE_REWRITE, modelName, null,
                    System.currentTimeMillis() - start, false, e.getMessage());
            return question;
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
