package com.smartkb.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartkb.ai.EmbeddingService;
import com.smartkb.chat.ChatMessageEntity;
import com.smartkb.doc.DocChunkEntity;
import com.smartkb.doc.KbDocumentEntity;
import com.smartkb.doc.mapper.DocChunkMapper;
import com.smartkb.doc.mapper.KbDocumentMapper;
import com.smartkb.vector.ScoredHit;
import com.smartkb.vector.VectorEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAG 检索流水线编排(核心类)
 *
 *   问题 --(1 多轮改写)--> 检索词
 *        --(2a 向量召回: embedding + 余弦Top-K)--+
 *        --(2b 关键词召回: 简化BM25)-------------+--(3 RRF融合)
 *        --(4 LLM重排过滤)--> 最终 Top-N 片段(带全链路得分, 可溯源)
 *
 * 每一步都可通过 rag.* 配置开关/调参, 且都有降级路径:
 * 没配 Key → 跳过改写/向量/重排, 纯关键词也能出结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final RagProperties props;
    private final EmbeddingService embeddingService;
    private final VectorEngine vectorEngine;
    private final KeywordRetriever keywordRetriever;
    private final QueryRewriter queryRewriter;
    private final LlmReranker reranker;
    private final DocChunkMapper chunkMapper;
    private final KbDocumentMapper documentMapper;

    /** 检索结果: 实际使用的检索词 + 最终片段列表 */
    public record RagResult(String searchQuery, boolean rewritten, List<RetrievedChunk> chunks) {
    }

    public RagResult retrieve(Long userId, long kbId, String question,
                              List<ChatMessageEntity> history, boolean allowRewrite) {
        // 1. 多轮查询改写(有历史且开关开启时)
        String searchQuery = question;
        if (allowRewrite && props.isQueryRewriteEnabled()
                && history != null && !history.isEmpty() && embeddingService.available()) {
            searchQuery = queryRewriter.rewrite(userId, history, question);
        }
        boolean rewritten = !searchQuery.equals(question);

        // 2a. 向量召回(没配 Key 时 embedQuery 返回 null, 自动跳过)
        float[] queryVector = embeddingService.embedQuery(userId, searchQuery);
        List<ScoredHit> vectorHits = queryVector == null
                ? List.of()
                : vectorEngine.search(kbId, queryVector, props.getVectorTopK(), props.getMinScore());

        // 2b. 关键词召回
        List<ScoredHit> keywordHits = keywordRetriever.search(kbId, searchQuery, props.getKeywordTopK());

        // 3. RRF 融合
        List<ScoredHit> fused = RrfFusion.fuse(List.of(vectorHits, keywordHits), props.getRrfK());
        if (fused.size() > props.getFusedTopK()) {
            fused = fused.subList(0, props.getFusedTopK());
        }
        List<RetrievedChunk> chunks = hydrate(fused, vectorHits, keywordHits);

        // 4. LLM 重排(可关闭; 失败自动兜底)
        if (props.isRerankEnabled() && embeddingService.available() && chunks.size() > 1) {
            chunks = reranker.rerank(userId, searchQuery, chunks, props.getRerankMinScore());
        }
        if (chunks.size() > props.getFinalTopK()) {
            chunks = chunks.subList(0, props.getFinalTopK());
        }
        log.debug("检索完成: query=[{}] 向量{}条 关键词{}条 -> 最终{}条",
                searchQuery, vectorHits.size(), keywordHits.size(), chunks.size());
        return new RagResult(searchQuery, rewritten, chunks);
    }

    /** 按融合排名回表补全片段内容与来源文档名, 并标注各路得分 */
    private List<RetrievedChunk> hydrate(List<ScoredHit> fused,
                                         List<ScoredHit> vectorHits, List<ScoredHit> keywordHits) {
        if (fused.isEmpty()) {
            return List.of();
        }
        List<Long> ids = fused.stream().map(ScoredHit::chunkId).toList();
        Map<Long, DocChunkEntity> chunkById = chunkMapper.selectList(
                        new LambdaQueryWrapper<DocChunkEntity>().in(DocChunkEntity::getId, ids))
                .stream().collect(Collectors.toMap(DocChunkEntity::getId, Function.identity()));

        Map<Long, String> docNames = new HashMap<>();
        chunkById.values().stream().map(DocChunkEntity::getDocumentId).distinct().forEach(docId -> {
            KbDocumentEntity doc = documentMapper.selectById(docId);
            docNames.put(docId, doc == null ? "未知文档" : doc.getName());
        });
        Map<Long, Double> vectorScore = vectorHits.stream()
                .collect(Collectors.toMap(ScoredHit::chunkId, ScoredHit::score, (a, b) -> a));
        Map<Long, Double> keywordScore = keywordHits.stream()
                .collect(Collectors.toMap(ScoredHit::chunkId, ScoredHit::score, (a, b) -> a));

        return fused.stream()
                .filter(hit -> chunkById.containsKey(hit.chunkId()))
                .map(hit -> {
                    DocChunkEntity entity = chunkById.get(hit.chunkId());
                    RetrievedChunk chunk = new RetrievedChunk();
                    chunk.setChunkId(entity.getId());
                    chunk.setDocumentId(entity.getDocumentId());
                    chunk.setDocName(docNames.get(entity.getDocumentId()));
                    chunk.setTitlePath(entity.getTitlePath());
                    chunk.setContent(entity.getContent());
                    chunk.setVectorScore(vectorScore.get(hit.chunkId()));
                    chunk.setKeywordScore(keywordScore.get(hit.chunkId()));
                    chunk.setRrfScore(hit.score());
                    return chunk;
                }).toList();
    }
}
