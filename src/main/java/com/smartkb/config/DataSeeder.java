package com.smartkb.config;

import com.smartkb.auth.AuthService;
import com.smartkb.auth.UserEntity;
import com.smartkb.auth.mapper.UserMapper;
import com.smartkb.doc.DocumentIndexer;
import com.smartkb.doc.KbDocumentEntity;
import com.smartkb.doc.mapper.KbDocumentMapper;
import com.smartkb.kb.KnowledgeBaseEntity;
import com.smartkb.kb.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 种子数据: 首次启动自动创建演示账号 demo/123456、
 * 示例知识库和计算机网络知识文档(异步索引), 克隆下来即可演示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserMapper userMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final KbDocumentMapper documentMapper;
    private final DocumentIndexer indexer;

    @Override
    public void run(String... args) {
        if (userMapper.selectCount(null) > 0) {
            return;     // 已初始化过
        }
        // 1. 演示账号 demo / 123456
        UserEntity user = new UserEntity();
        user.setUsername("demo");
        user.setSalt(HexFormat.of().formatHex("smartkb-demo".getBytes()));
        user.setPassword(AuthService.sha256(user.getSalt() + "123456"));
        user.setNickname("演示用户");
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);

        // 2. 示例知识库
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setUserId(user.getId());
        kb.setName("计算机网络知识库");
        kb.setDescription("计算机网络基础知识与常用协议，可直接提问体验 RAG 检索与引用效果");
        kb.setChunkSize(400);
        kb.setChunkOverlap(60);
        kb.setCreatedAt(LocalDateTime.now());
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.insert(kb);

        // 3. 示例文档(异步索引, 未配 API Key 时自动降级为关键词检索)
        seedDocument(user.getId(), kb.getId(), "计算机网络知识点.md");
        log.info("种子数据初始化完成: 账号 demo/123456, 知识库 [{}]", kb.getName());
    }

    private void seedDocument(Long userId, Long kbId, String filename) {
        try {
            byte[] bytes = new ClassPathResource("seed/" + filename).getInputStream().readAllBytes();

            KbDocumentEntity doc = new KbDocumentEntity();
            doc.setKbId(kbId);
            doc.setUserId(userId);
            doc.setName(filename);
            doc.setFileType("md");
            doc.setFileSize((long) bytes.length);
            doc.setStatus(KbDocumentEntity.STATUS_PENDING);
            doc.setChunkCount(0);
            doc.setCharCount(0);
            doc.setCreatedAt(LocalDateTime.now());
            documentMapper.insert(doc);

            // 落盘一份原始文件, 支持后续"重建索引"
            Path dir = Path.of("data", "files");
            Files.createDirectories(dir);
            Files.write(dir.resolve(doc.getId() + ".md"), bytes);

            indexer.index(doc.getId(), bytes);
        } catch (IOException e) {
            log.warn("示例文档 {} 初始化失败: {}", filename, e.getMessage());
        }
    }
}
