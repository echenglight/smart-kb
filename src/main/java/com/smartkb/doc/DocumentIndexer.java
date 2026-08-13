package com.smartkb.doc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartkb.ai.EmbeddingService;
import com.smartkb.common.JsonUtils;
import com.smartkb.doc.mapper.DocChunkMapper;
import com.smartkb.doc.mapper.KbDocumentMapper;
import com.smartkb.doc.split.SmartTextSplitter;
import com.smartkb.doc.split.TextChunk;
import com.smartkb.kb.KnowledgeBaseEntity;
import com.smartkb.kb.mapper.KnowledgeBaseMapper;
import com.smartkb.vector.VectorEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档索引流水线(异步): 解析 → 分块 → 向量化 → 落库
 *
 * 上传接口只落一条 PENDING 记录并把字节流投递到 indexExecutor 线程池,
 * 立即返回; 前端轮询状态。大文件解析+向量化可达分钟级, 同步做会拖死请求线程。
 *
 * 注意 @Async 必须放在独立的 Bean 上, 从 DocumentService 内部 this 调用
 * 不会经过代理，因此异步不生效；这是 Spring AOP 代理机制的重要约束。
 *
 * 降级设计: 向量化失败(没配 Key / 网络断)不算索引失败, 分块照常入库,
 * 检索自动退化为纯关键词模式, errorMsg 记录降级原因供前端提示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIndexer {

    private final KbDocumentMapper documentMapper;
    private final DocChunkMapper chunkMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final DocumentParser parser;
    private final SmartTextSplitter splitter;
    private final EmbeddingService embeddingService;
    private final VectorEngine vectorEngine;

    @Async("indexExecutor")
    public void index(long docId, byte[] bytes) {
        KbDocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null) {
            return;
        }
        doc.setStatus(KbDocumentEntity.STATUS_PARSING);
        doc.setErrorMsg(null);
        documentMapper.updateById(doc);

        try {
            // 1. 解析成纯文本
            String text = parser.parse(doc.getFileType(), bytes);

            // 2. 按知识库配置分块
            KnowledgeBaseEntity kb = kbMapper.selectById(doc.getKbId());
            List<TextChunk> chunks = splitter.split(text,
                    kb == null || kb.getChunkSize() == null ? 500 : kb.getChunkSize(),
                    kb == null || kb.getChunkOverlap() == null ? 80 : kb.getChunkOverlap());
            if (chunks.isEmpty()) {
                fail(doc, "未解析出有效文本内容");
                return;
            }

            // 3. 清理旧分块(重建索引场景)后入库
            chunkMapper.delete(new LambdaQueryWrapper<DocChunkEntity>()
                    .eq(DocChunkEntity::getDocumentId, docId));
            List<DocChunkEntity> entities = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                TextChunk chunk = chunks.get(i);
                DocChunkEntity entity = new DocChunkEntity();
                entity.setDocumentId(docId);
                entity.setKbId(doc.getKbId());
                entity.setChunkIndex(i);
                entity.setContent(chunk.content());
                entity.setTitlePath(chunk.titlePath());
                entity.setCharCount(chunk.content().length());
                entity.setCreatedAt(LocalDateTime.now());
                chunkMapper.insert(entity);
                entities.add(entity);
            }

            // 4. 批量向量化(可降级)
            String degradeNote = embed(doc, entities);

            doc.setStatus(KbDocumentEntity.STATUS_INDEXED);
            doc.setChunkCount(entities.size());
            doc.setCharCount(text.length());
            doc.setErrorMsg(degradeNote);
            documentMapper.updateById(doc);
            vectorEngine.invalidate(doc.getKbId());
            log.info("文档 [{}] 索引完成: {} 块, {} 字符{}", doc.getName(), entities.size(),
                    text.length(), degradeNote == null ? "" : " (" + degradeNote + ")");
        } catch (Exception e) {
            log.error("文档 [{}] 索引失败", doc.getName(), e);
            fail(doc, e.getMessage());
        }
    }

    /** 向量化并回填 embedding 列; 返回降级说明(null 表示全部成功) */
    private String embed(KbDocumentEntity doc, List<DocChunkEntity> entities) {
        if (!embeddingService.available()) {
            return "向量检索未启用(关键词检索可用)";
        }
        try {
            // 标题链路拼进待向量化文本, 给 embedding 补充章节上下文
            List<String> texts = entities.stream()
                    .map(c -> c.getTitlePath() == null || c.getTitlePath().isEmpty()
                            ? c.getContent()
                            : c.getTitlePath() + "\n" + c.getContent())
                    .toList();
            List<float[]> vectors = embeddingService.embedBatch(doc.getUserId(), texts);
            for (int i = 0; i < entities.size() && i < vectors.size(); i++) {
                DocChunkEntity update = new DocChunkEntity();
                update.setId(entities.get(i).getId());
                update.setEmbedding(JsonUtils.toJson(vectors.get(i)));
                chunkMapper.updateById(update);
            }
            return null;
        } catch (Exception e) {
            log.warn("文档 [{}] 向量化失败, 降级为关键词检索: {}", doc.getName(), e.getMessage());
            return "向量化失败已降级为关键词检索: " + e.getMessage();
        }
    }

    private void fail(KbDocumentEntity doc, String message) {
        doc.setStatus(KbDocumentEntity.STATUS_FAILED);
        doc.setErrorMsg(message == null ? "未知错误" : message);
        documentMapper.updateById(doc);
    }
}
