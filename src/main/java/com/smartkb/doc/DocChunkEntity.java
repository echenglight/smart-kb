package com.smartkb.doc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/** 文本分块: RAG 检索的最小单元 */
@Data
@TableName("doc_chunk")
public class DocChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long kbId;

    /** 在文档内的序号, 从 0 开始 */
    private Integer chunkIndex;

    private String content;

    /** 标题链路(markdown 标题感知分块产出), 如 "线程池 > 核心参数" */
    private String titlePath;

    private Integer charCount;

    /** 1024 维向量的 JSON 数组字符串; null = 未向量化, 仅关键词可检索 */
    @JsonIgnore
    private String embedding;

    private LocalDateTime createdAt;
}
