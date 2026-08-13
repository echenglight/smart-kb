package com.smartkb.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 流水线参数(application.yml 的 rag.* 前缀)
 *
 * 集中可调便于在检索质量不理想时系统化调整参数：
 * 召回条数、相似度阈值、RRF 常数、重排开关/阈值、历史窗口。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** 向量召回条数 */
    private int vectorTopK = 8;
    /** 关键词召回条数 */
    private int keywordTopK = 8;
    /** RRF 融合后保留条数 */
    private int fusedTopK = 6;
    /** 重排后最终进入 prompt 的条数 */
    private int finalTopK = 4;
    /** RRF 平滑常数, 论文默认 60 */
    private int rrfK = 60;
    /** 向量余弦相似度下限, 低于此认为不相关 */
    private double minScore = 0.35;
    /** 多轮对话是否启用 LLM 查询改写 */
    private boolean queryRewriteEnabled = true;
    /** 是否启用 LLM 重排序 */
    private boolean rerankEnabled = true;
    /** 重排得分(0-10)低于此的片段丢弃 */
    private int rerankMinScore = 4;
    /** 对话携带的历史消息条数 */
    private int historyWindow = 6;
}
