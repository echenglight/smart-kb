package com.smartkb.rag;

import lombok.Data;

/**
 * 检索结果片段: 携带全链路得分, 前端"检索过程"面板据此展示
 * 每个片段是向量召回、关键词召回还是双路命中, 以及重排后的最终得分。
 */
@Data
public class RetrievedChunk {

    private Long chunkId;
    private Long documentId;
    private String docName;
    private String titlePath;
    private String content;

    /** 向量余弦相似度(未被向量路召回则为 null) */
    private Double vectorScore;
    /** 关键词匹配得分(未被关键词路召回则为 null) */
    private Double keywordScore;
    /** RRF 融合得分 */
    private Double rrfScore;
    /** LLM 重排得分 0-10(未启用重排则为 null) */
    private Double rerankScore;
}
