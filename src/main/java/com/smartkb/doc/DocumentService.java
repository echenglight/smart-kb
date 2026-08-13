package com.smartkb.doc;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartkb.common.BizException;
import com.smartkb.doc.mapper.DocChunkMapper;
import com.smartkb.doc.mapper.KbDocumentMapper;
import com.smartkb.kb.KnowledgeBaseService;
import com.smartkb.vector.VectorEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理: 上传即返回, 索引交给异步流水线(DocumentIndexer)
 *
 * 原始文件落盘到 data/files/{docId}.{ext}, 支持"重建索引"
 * (调整分块参数后不用重新上传)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final KbDocumentMapper documentMapper;
    private final DocChunkMapper chunkMapper;
    private final KnowledgeBaseService kbService;
    private final DocumentIndexer indexer;
    private final VectorEngine vectorEngine;

    private static final Path FILE_DIR = Path.of("data", "files");
    private static final long MAX_FILE_SIZE = 30L * 1024 * 1024;

    public KbDocumentEntity upload(Long kbId, MultipartFile file) {
        kbService.checkOwned(kbId);
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BizException("文件大小不能超过 30MB");
        }
        String filename = DocumentParser.safeFilename(file.getOriginalFilename());
        if (filename.isBlank()) {
            throw new BizException("文件名不能为空");
        }
        String ext = DocumentParser.extOf(filename);
        if (!DocumentParser.SUPPORTED.contains(ext)) {
            throw new BizException("不支持的文件类型: " + ext + ", 支持 " + DocumentParser.SUPPORTED);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(500, "读取上传文件失败: " + e.getMessage());
        }

        KbDocumentEntity doc = new KbDocumentEntity();
        doc.setKbId(kbId);
        doc.setUserId(StpUtil.getLoginIdAsLong());
        doc.setName(filename);
        doc.setFileType(ext);
        doc.setFileSize(file.getSize());
        doc.setStatus(KbDocumentEntity.STATUS_PENDING);
        doc.setChunkCount(0);
        doc.setCharCount(0);
        doc.setCreatedAt(LocalDateTime.now());
        documentMapper.insert(doc);

        saveFile(doc, bytes);
        indexer.index(doc.getId(), bytes);      // 异步, 立即返回
        kbService.touch(kbId);
        return doc;
    }

    public List<KbDocumentEntity> list(Long kbId) {
        kbService.checkOwned(kbId);
        return documentMapper.selectList(new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getKbId, kbId)
                .orderByDesc(KbDocumentEntity::getId));
    }

    public KbDocumentEntity get(Long docId) {
        return checkOwnedDoc(docId);
    }

    /** 分块列表(带是否已向量化标记), 供前端查看分块质量 */
    public List<Map<String, Object>> chunks(Long docId) {
        checkOwnedDoc(docId);
        List<DocChunkEntity> list = chunkMapper.selectList(new LambdaQueryWrapper<DocChunkEntity>()
                .eq(DocChunkEntity::getDocumentId, docId)
                .orderByAsc(DocChunkEntity::getChunkIndex)
                .last("LIMIT 1000"));
        return list.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("chunkIndex", c.getChunkIndex());
            m.put("titlePath", c.getTitlePath());
            m.put("content", c.getContent());
            m.put("charCount", c.getCharCount());
            m.put("embedded", c.getEmbedding() != null);
            return m;
        }).toList();
    }

    /** 重建索引: 调整分块参数后基于落盘的原始文件重跑流水线 */
    public KbDocumentEntity reindex(Long docId) {
        KbDocumentEntity doc = checkOwnedDoc(docId);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(fileOf(doc));
        } catch (IOException e) {
            throw new BizException("原始文件已不存在, 请重新上传");
        }
        doc.setStatus(KbDocumentEntity.STATUS_PENDING);
        doc.setErrorMsg(null);
        documentMapper.updateById(doc);
        indexer.index(docId, bytes);
        return doc;
    }

    public void delete(Long docId) {
        KbDocumentEntity doc = checkOwnedDoc(docId);
        chunkMapper.delete(new LambdaQueryWrapper<DocChunkEntity>()
                .eq(DocChunkEntity::getDocumentId, docId));
        documentMapper.deleteById(docId);
        try {
            Files.deleteIfExists(fileOf(doc));
        } catch (IOException e) {
            log.warn("删除原始文件失败: {}", e.getMessage());
        }
        vectorEngine.invalidate(doc.getKbId());
        kbService.touch(doc.getKbId());
    }

    private KbDocumentEntity checkOwnedDoc(Long docId) {
        KbDocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null || doc.getUserId() != StpUtil.getLoginIdAsLong()) {
            throw new BizException(404, "文档不存在");
        }
        return doc;
    }

    private void saveFile(KbDocumentEntity doc, byte[] bytes) {
        try {
            Files.createDirectories(FILE_DIR);
            Files.write(fileOf(doc), bytes);
        } catch (IOException e) {
            log.warn("原始文件落盘失败(不影响本次索引, 但无法重建): {}", e.getMessage());
        }
    }

    private Path fileOf(KbDocumentEntity doc) {
        return FILE_DIR.resolve(doc.getId() + "." + doc.getFileType());
    }
}
