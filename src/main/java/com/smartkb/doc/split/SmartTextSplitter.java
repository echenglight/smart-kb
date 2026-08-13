package com.smartkb.doc.split;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能分块器: 结构感知 + 语义边界优先
 *
 * 通用切块(如 Spring AI 的 TokenTextSplitter)按固定 token 数硬切,
 * 会把一句话拦腰截断、把标题和正文拆散, 检索质量差。本实现的策略:
 *
 *  1. 标题感知: 识别 Markdown 标题, 按章节切分, 每块携带标题链路(titlePath),
 *     检索命中后能告诉用户"这段话出自哪一节", 也给了 embedding 更多上下文;
 *  2. 语义边界: 章节内先按段落切, 超长段落再按句子切, 绝不从句子中间断开
 *     (仅当单句超长时才退化为硬切);
 *  3. 滑动重叠(overlap): 相邻块共享尾部文本, 避免答案恰好横跨两块边界被切碎。
 *
 * chunk 大小需要权衡：块太小语义不完整、召回后信息不足；
 * 块太大噪声多、稀释相似度且挤占 prompt 窗口。经验值 300~800 字符, 本项目按库可配。
 */
@Component
public class SmartTextSplitter {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern PARAGRAPH_SEP = Pattern.compile("\\n\\s*\\n");
    /** 在中英文句末标点后断句(保留标点) */
    private static final Pattern SENTENCE_SEP = Pattern.compile("(?<=[。！？；!?;])");
    /** 小于该长度的碎片视为噪声丢弃 */
    private static final int MIN_CHUNK_CHARS = 10;

    public List<TextChunk> split(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        overlap = Math.min(overlap, chunkSize / 2);   // 防呆: 重叠不超过块的一半

        List<TextChunk> result = new ArrayList<>();
        for (Section section : parseSections(text.replace("\r\n", "\n"))) {
            result.addAll(splitSection(section, chunkSize, overlap));
        }
        return result;
    }

    // ==================== 第一步: 按 Markdown 标题切章节 ====================

    private record Section(String titlePath, String body) {
    }

    private List<Section> parseSections(String text) {
        List<Section> sections = new ArrayList<>();
        // titleStack[level-1] = 该级标题, 组成链路如 "线程池 > 核心参数"
        String[] titleStack = new String[6];
        StringBuilder body = new StringBuilder();
        String currentPath = "";

        for (String line : text.split("\n", -1)) {
            Matcher m = HEADING.matcher(line.strip());
            if (m.matches()) {
                flushSection(sections, currentPath, body);
                int level = m.group(1).length();
                titleStack[level - 1] = m.group(2).strip();
                for (int i = level; i < 6; i++) {
                    titleStack[i] = null;      // 出现高级标题时清空更深层级
                }
                currentPath = joinPath(titleStack);
            } else {
                body.append(line).append('\n');
            }
        }
        flushSection(sections, currentPath, body);
        return sections;
    }

    private void flushSection(List<Section> sections, String path, StringBuilder body) {
        String content = body.toString().strip();
        if (!content.isEmpty()) {
            sections.add(new Section(path, content));
        }
        body.setLength(0);
    }

    private String joinPath(String[] stack) {
        StringBuilder sb = new StringBuilder();
        for (String title : stack) {
            if (title != null) {
                if (sb.length() > 0) {
                    sb.append(" > ");
                }
                sb.append(title);
            }
        }
        return sb.toString();
    }

    // ============ 第二步: 章节内按 段落→句子 累积成块, 带滑动重叠 ============

    private List<TextChunk> splitSection(Section section, int chunkSize, int overlap) {
        // 先拆成不可再分的最小单元: 段落, 超长段落降级为句子
        List<String> units = new ArrayList<>();
        for (String para : PARAGRAPH_SEP.split(section.body())) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= chunkSize) {
                units.add(p);
            } else {
                for (String sentence : SENTENCE_SEP.split(p)) {
                    String s = sentence.strip();
                    if (s.isEmpty()) {
                        continue;
                    }
                    if (s.length() <= chunkSize) {
                        units.add(s);
                    } else {
                        // 无标点超长句(如表格/代码), 退化为按长度硬切
                        for (int i = 0; i < s.length(); i += chunkSize) {
                            units.add(s.substring(i, Math.min(s.length(), i + chunkSize)));
                        }
                    }
                }
            }
        }

        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.length() > 0 && current.length() + unit.length() + 1 > chunkSize) {
                String content = current.toString().strip();
                chunks.add(new TextChunk(content, section.titlePath()));
                // 滑动重叠: 新块以上一块的尾部开头
                current = new StringBuilder(tail(content, overlap));
                if (current.length() > 0) {
                    current.append('\n');
                }
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(unit);
        }
        if (current.toString().strip().length() >= MIN_CHUNK_CHARS) {
            chunks.add(new TextChunk(current.toString().strip(), section.titlePath()));
        }
        return chunks;
    }

    private String tail(String text, int overlap) {
        if (overlap <= 0 || text.length() <= overlap) {
            return overlap <= 0 ? "" : text;
        }
        return text.substring(text.length() - overlap);
    }
}
