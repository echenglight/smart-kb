package com.smartkb.stats;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartkb.stats.mapper.AiCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 用量统计: 记录每次 AI 调用的 token 与耗时, 汇总给统计页 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private final AiCallLogMapper logMapper;

    /** 记一条调用流水(内部吞异常: 统计失败不能影响主流程) */
    public void log(Long userId, String callType, String model, Usage usage,
                    long latencyMs, boolean success, String remark) {
        try {
            AiCallLog entry = new AiCallLog();
            entry.setUserId(userId);
            entry.setCallType(callType);
            entry.setModel(model);
            entry.setPromptTokens(usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens());
            entry.setCompletionTokens(usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
            entry.setTotalTokens(usage == null || usage.getTotalTokens() == null ? 0 : usage.getTotalTokens());
            entry.setLatencyMs(latencyMs);
            entry.setSuccess(success);
            entry.setRemark(remark);
            entry.setCreatedAt(LocalDateTime.now());
            logMapper.insert(entry);
        } catch (Exception e) {
            log.warn("记录 AI 调用流水失败: {}", e.getMessage());
        }
    }

    /** 统计总览: 总调用数/总token/平均耗时/按类型分布/最近流水 */
    public Map<String, Object> overview(Long userId) {
        List<AiCallLog> logs = logMapper.selectList(
                new LambdaQueryWrapper<AiCallLog>()
                        .eq(AiCallLog::getUserId, userId)
                        .orderByDesc(AiCallLog::getId)
                        .last("LIMIT 500"));

        long totalTokens = 0;
        long totalLatency = 0;
        long failures = 0;
        Map<String, long[]> byType = new HashMap<>();   // type -> [次数, tokens]
        for (AiCallLog entry : logs) {
            totalTokens += entry.getTotalTokens() == null ? 0 : entry.getTotalTokens();
            totalLatency += entry.getLatencyMs() == null ? 0 : entry.getLatencyMs();
            if (Boolean.FALSE.equals(entry.getSuccess())) {
                failures++;
            }
            long[] agg = byType.computeIfAbsent(entry.getCallType(), k -> new long[2]);
            agg[0]++;
            agg[1] += entry.getTotalTokens() == null ? 0 : entry.getTotalTokens();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalCalls", logs.size());
        result.put("totalTokens", totalTokens);
        result.put("avgLatencyMs", logs.isEmpty() ? 0 : totalLatency / logs.size());
        result.put("failures", failures);
        result.put("byType", byType.entrySet().stream().collect(
                HashMap::new,
                (m, e) -> m.put(e.getKey(), Map.of("calls", e.getValue()[0], "tokens", e.getValue()[1])),
                HashMap::putAll));
        result.put("recent", logs.stream().limit(20).toList());
        return result;
    }
}
