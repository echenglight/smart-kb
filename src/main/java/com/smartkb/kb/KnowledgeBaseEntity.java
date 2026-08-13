package com.smartkb.kb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_base")
public class KnowledgeBaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private String description;

    /** 分块目标字符数, 上传文档时生效 */
    private Integer chunkSize;

    /** 相邻块重叠字符数 */
    private Integer chunkOverlap;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 文档数(查询时填充, 不落库) */
    @TableField(exist = false)
    private Long docCount;

    /** 分块数(查询时填充, 不落库) */
    @TableField(exist = false)
    private Long chunkCount;
}
