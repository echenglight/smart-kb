package com.smartkb.stats;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 调用流水: 每次大模型/Embedding 调用记一条, 支撑成本统计 */
@Data
@TableName("ai_call_log")
public class AiCallLog {

    public static final String TYPE_CHAT = "CHAT";
    public static final String TYPE_EMBEDDING = "EMBEDDING";
    public static final String TYPE_REWRITE = "REWRITE";
    public static final String TYPE_RERANK = "RERANK";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String callType;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long latencyMs;

    private Boolean success;

    private String remark;

    private LocalDateTime createdAt;
}
