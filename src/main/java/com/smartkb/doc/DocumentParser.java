package com.smartkb.doc;

import com.smartkb.common.BizException;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档解析: 统一转成纯文本
 *
 * Markdown/TXT 直接按 UTF-8 读取 —— 保留 # 标题符号, 供分块器做标题感知;
 * PDF/Word/HTML 等交给 Apache Tika 抽取正文。
 */
@Component
public class DocumentParser {

    public static final Set<String> SUPPORTED = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx", "md", "markdown", "txt", "html", "htm");

    private static final Set<String> PLAIN_TEXT = Set.of("md", "markdown", "txt");

    public String parse(String fileType, byte[] bytes) {
        if (PLAIN_TEXT.contains(fileType)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(bytes));
            List<Document> documents = reader.read();
            return documents.stream()
                    .map(Document::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new BizException(500, "文档解析失败: " + e.getMessage());
        }
    }

    public static String extOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 浏览器通常只传文件名，但恶意客户端可传完整路径或控制字符。
     * 这里只保留最后一段用于展示，磁盘文件名仍由 docId 生成。
     */
    public static String safeFilename(String filename) {
        if (filename == null) {
            return "";
        }
        String normalized = filename.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (normalized.length() > 255) {
            normalized = normalized.substring(normalized.length() - 255);
        }
        return normalized;
    }
}
