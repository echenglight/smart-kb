package com.smartkb.kb;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartkb.common.BizException;
import com.smartkb.doc.DocChunkEntity;
import com.smartkb.doc.KbDocumentEntity;
import com.smartkb.doc.mapper.DocChunkMapper;
import com.smartkb.doc.mapper.KbDocumentMapper;
import com.smartkb.kb.mapper.KnowledgeBaseMapper;
import com.smartkb.vector.VectorEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库管理
 *
 * 所有读写都先做归属校验(checkOwned): 防止水平越权 ——
 * 用户 A 即使构造用户 B 的 kbId，也无法读写不属于自己的数据。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;
    private final KbDocumentMapper documentMapper;
    private final DocChunkMapper chunkMapper;
    private final VectorEngine vectorEngine;

    public List<KnowledgeBaseEntity> listMine() {
        long userId = StpUtil.getLoginIdAsLong();
        List<KnowledgeBaseEntity> list = kbMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBaseEntity>()
                        .eq(KnowledgeBaseEntity::getUserId, userId)
                        .orderByDesc(KnowledgeBaseEntity::getUpdatedAt));
        for (KnowledgeBaseEntity kb : list) {
            kb.setDocCount(documentMapper.selectCount(
                    new LambdaQueryWrapper<KbDocumentEntity>().eq(KbDocumentEntity::getKbId, kb.getId())));
            kb.setChunkCount(chunkMapper.selectCount(
                    new LambdaQueryWrapper<DocChunkEntity>().eq(DocChunkEntity::getKbId, kb.getId())));
        }
        return list;
    }

    public KnowledgeBaseEntity create(String name, String description, Integer chunkSize, Integer chunkOverlap) {
        if (name == null || name.isBlank()) {
            throw new BizException("知识库名称不能为空");
        }
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setUserId(StpUtil.getLoginIdAsLong());
        kb.setName(name.trim());
        kb.setDescription(description);
        kb.setChunkSize(normalize(chunkSize, 100, 2000, 500));
        kb.setChunkOverlap(normalize(chunkOverlap, 0, 500, 80));
        kb.setCreatedAt(LocalDateTime.now());
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.insert(kb);
        return kb;
    }

    public KnowledgeBaseEntity update(Long id, String name, String description,
                                      Integer chunkSize, Integer chunkOverlap) {
        KnowledgeBaseEntity kb = checkOwned(id);
        if (name != null && !name.isBlank()) {
            kb.setName(name.trim());
        }
        kb.setDescription(description);
        if (chunkSize != null) {
            kb.setChunkSize(normalize(chunkSize, 100, 2000, 500));
        }
        if (chunkOverlap != null) {
            kb.setChunkOverlap(normalize(chunkOverlap, 0, 500, 80));
        }
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.updateById(kb);
        return kb;
    }

    /** 级联删除: 库 -> 文档 -> 分块, 并使向量缓存失效 */
    @Transactional
    public void delete(Long id) {
        checkOwned(id);
        chunkMapper.delete(new LambdaQueryWrapper<DocChunkEntity>().eq(DocChunkEntity::getKbId, id));
        documentMapper.delete(new LambdaQueryWrapper<KbDocumentEntity>().eq(KbDocumentEntity::getKbId, id));
        kbMapper.deleteById(id);
        vectorEngine.invalidate(id);
    }

    /** 归属校验: 不存在或不属于当前用户都抛 404, 不泄露资源是否存在 */
    public KnowledgeBaseEntity checkOwned(Long id) {
        KnowledgeBaseEntity kb = kbMapper.selectById(id);
        if (kb == null || kb.getUserId() != StpUtil.getLoginIdAsLong()) {
            throw new BizException(404, "知识库不存在");
        }
        return kb;
    }

    public void touch(Long id) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(id);
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.updateById(kb);
    }

    private int normalize(Integer value, int min, int max, int dft) {
        if (value == null) {
            return dft;
        }
        return Math.max(min, Math.min(max, value));
    }
}
