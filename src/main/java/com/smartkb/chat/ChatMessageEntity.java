package com.smartkb.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话消息: assistant 消息额外携带引用片段与检索词, 支持答案溯源回放 */
@Data
@TableName("chat_message")
public class ChatMessageEntity {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private String role;

    private String content;

    /** 引用片段 JSON 数组(仅 assistant) */
    private String citations;

    /** 改写后的检索词(仅 assistant, 多轮改写生效时) */
    private String rewrittenQuery;

    private Integer promptTokens;

    private Integer completionTokens;

    private LocalDateTime createdAt;
}
