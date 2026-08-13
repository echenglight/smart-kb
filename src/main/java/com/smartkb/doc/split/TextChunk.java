package com.smartkb.doc.split;

/** 分块器输出: 正文 + 标题链路 */
public record TextChunk(String content, String titlePath) {
}
