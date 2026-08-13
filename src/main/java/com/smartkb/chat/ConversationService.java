package com.smartkb.chat;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartkb.chat.mapper.ChatMessageMapper;
import com.smartkb.chat.mapper.ConversationMapper;
import com.smartkb.common.BizException;
import com.smartkb.kb.KnowledgeBaseEntity;
import com.smartkb.kb.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 会话管理: 每个会话绑定一个知识库, 消息持久化, 重启不丢历史 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final KnowledgeBaseService kbService;

    public List<ConversationEntity> listMine() {
        List<ConversationEntity> list = conversationMapper.selectList(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getUserId, StpUtil.getLoginIdAsLong())
                        .orderByDesc(ConversationEntity::getUpdatedAt)
                        .last("LIMIT 100"));
        for (ConversationEntity conv : list) {
            try {
                KnowledgeBaseEntity kb = kbService.checkOwned(conv.getKbId());
                conv.setKbName(kb.getName());
            } catch (BizException e) {
                conv.setKbName("(已删除)");
            }
        }
        return list;
    }

    public ConversationEntity create(Long kbId) {
        kbService.checkOwned(kbId);
        ConversationEntity conv = new ConversationEntity();
        conv.setUserId(StpUtil.getLoginIdAsLong());
        conv.setKbId(kbId);
        conv.setTitle("新对话");
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conv);
        return conv;
    }

    public List<ChatMessageEntity> messages(Long conversationId) {
        checkOwned(conversationId);
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId)
                .orderByAsc(ChatMessageEntity::getId));
    }

    @Transactional
    public void delete(Long conversationId) {
        checkOwned(conversationId);
        messageMapper.delete(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);
    }

    public ConversationEntity checkOwned(Long conversationId) {
        ConversationEntity conv = conversationMapper.selectById(conversationId);
        if (conv == null || conv.getUserId() != StpUtil.getLoginIdAsLong()) {
            throw new BizException(404, "会话不存在");
        }
        return conv;
    }

    /** 首条提问作为会话标题; 每次提问刷新活跃时间 */
    public void touch(ConversationEntity conv, String question) {
        if (conv.getTitle() == null || conv.getTitle().isBlank() || "新对话".equals(conv.getTitle())) {
            conv.setTitle(question.length() > 30 ? question.substring(0, 30) : question);
        }
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
    }

    /** 最近 N 条历史(升序返回), 供改写与多轮上下文 */
    public List<ChatMessageEntity> recentMessages(Long conversationId, int limit) {
        List<ChatMessageEntity> desc = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .orderByDesc(ChatMessageEntity::getId)
                        .last("LIMIT " + limit));
        java.util.Collections.reverse(desc);
        return desc;
    }
}
