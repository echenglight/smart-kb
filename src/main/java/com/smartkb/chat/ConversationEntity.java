package com.smartkb.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class ConversationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 绑定的知识库: 问答只在此库内检索 */
    private Long kbId;

    private String title;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 知识库名称(查询时填充) */
    @TableField(exist = false)
    private String kbName;
}
