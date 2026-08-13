package com.smartkb.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartkb.doc.DocChunkEntity;
import com.smartkb.doc.mapper.DocChunkMapper;
import com.smartkb.vector.ScoredHit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词检索路(简化版 BM25)
 *
 * 为什么向量检索之外还要关键词路: embedding 对"精确匹配"不敏感 ——
 * 专有名词、型号、报错码(如 "ORA-00942"、"ThreadPoolExecutor")在向量空间里
 * 可能与近义表述距离很近但与原词不完全对齐, 关键词路保证字面命中一定能召回。
 * 这就是混合检索(Hybrid Search)的动机, 生产可替换为 Elasticsearch BM25。
 *
 * 中文没有空格分词, 这里用轻量策略: 连续中文串整体 + 二字滑窗(bigram),
 * 生产可换 IK/jieba 分词。得分 = Σ 词频 × log(1+词长) / log(10+文长):
 * 长词更有区分度加权高, 除以文长抑制"长文档天然高分"。
 */
@Component
@RequiredArgsConstructor
public class KeywordRetriever {

    private final DocChunkMapper chunkMapper;

    private static final Pattern TOKEN = Pattern.compile("[\\u4e00-\\u9fa5]+|[a-zA-Z0-9_\\-.]+");
    /** 句尾疑问短语: 对检索无意义, 先剥掉 */
    private static final Pattern QUESTION_SUFFIX =
            Pattern.compile("(有哪些|是什么|是多少|怎么办|怎么样|为什么|如何|哪些|什么|吗|呢|[?？。!！])+$");
    /** 中文串内的助词切分符: "线程池的拒绝策略" -> "线程池" + "拒绝策略" */
    private static final Pattern CJK_PARTICLE = Pattern.compile("[的了吗呢啊吧呀]");
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "对", "有", "吗", "呢", "什么", "怎么", "如何",
            "为什么", "请问", "一下", "关于", "介绍", "the", "a", "an", "is", "are", "what", "how");
    private static final int MAX_TERMS = 12;
    private static final int MAX_CANDIDATES = 300;

    public List<ScoredHit> search(long kbId, String query, int topK) {
        List<String> terms = extractTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        // 候选集: 任一关键词 LIKE 命中(限制条数防止全表拉回内存)
        LambdaQueryWrapper<DocChunkEntity> wrapper = new LambdaQueryWrapper<DocChunkEntity>()
                .select(DocChunkEntity::getId, DocChunkEntity::getContent, DocChunkEntity::getTitlePath)
                .eq(DocChunkEntity::getKbId, kbId)
                .and(w -> {
                    for (String term : terms) {
                        w.or(o -> o.like(DocChunkEntity::getContent, term));
                    }
                })
                .last("LIMIT " + MAX_CANDIDATES);
        List<DocChunkEntity> candidates = chunkMapper.selectList(wrapper);

        List<ScoredHit> hits = new ArrayList<>();
        for (DocChunkEntity chunk : candidates) {
            String text = (chunk.getTitlePath() == null ? "" : chunk.getTitlePath() + "\n")
                    + chunk.getContent();
            double score = 0;
            for (String term : terms) {
                int tf = countOccurrences(text, term);
                if (tf > 0) {
                    score += tf * Math.log(1 + term.length());
                }
            }
            if (score > 0) {
                hits.add(new ScoredHit(chunk.getId(), score / Math.log(10 + text.length())));
            }
        }
        hits.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        return hits.size() > topK ? hits.subList(0, topK) : hits;
    }

    /** 提取检索词: 剥疑问尾缀 -> 按助词切中文串 -> 整词 + bigram 滑窗, 去停用词去重 */
    public List<String> extractTerms(String query) {
        if (query == null) {
            return List.of();
        }
        String cleaned = QUESTION_SUFFIX.matcher(query.strip()).replaceAll("");
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(cleaned);
        while (matcher.find() && terms.size() < MAX_TERMS) {
            String token = matcher.group();
            if (token.charAt(0) >= '一') {
                // 中文串: 先按助词拆开("线程池的拒绝策略" -> 线程池/拒绝策略)
                for (String sub : CJK_PARTICLE.split(token)) {
                    addCjkTerm(terms, sub);
                }
            } else if (token.length() >= 2 && !STOP_WORDS.contains(token.toLowerCase())) {
                terms.add(token);
            }
        }
        return new ArrayList<>(terms);
    }

    private void addCjkTerm(Set<String> terms, String token) {
        if (token.length() < 2 || STOP_WORDS.contains(token) || terms.size() >= MAX_TERMS) {
            return;
        }
        terms.add(token);
        // 长串补充二字滑窗, 提升召回(简易分词)
        for (int i = 0; i + 2 <= token.length() && terms.size() < MAX_TERMS && token.length() > 2; i++) {
            String bigram = token.substring(i, i + 2);
            if (!STOP_WORDS.contains(bigram)) {
                terms.add(bigram);
            }
        }
    }

    private int countOccurrences(String text, String term) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(term, index)) >= 0) {
            count++;
            index += term.length();
        }
        return count;
    }
}
