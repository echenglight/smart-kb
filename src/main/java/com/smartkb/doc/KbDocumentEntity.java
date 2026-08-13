package com.smartkb.doc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 文档记录, 状态机: PENDING -> PARSING -> INDEXED / FAILED */
@Data
@TableName("kb_document")
public class KbDocumentEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_INDEXED = "INDEXED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private Long userId;

    private String name;

    private String fileType;

    private Long fileSize;

    private String status;

    private Integer chunkCount;

    private Integer charCount;

    /** 失败原因; 向量化降级时也在此提示(状态仍为 INDEXED) */
    private String errorMsg;

    private LocalDateTime createdAt;
}
