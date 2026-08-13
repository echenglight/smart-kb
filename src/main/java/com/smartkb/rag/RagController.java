package com.smartkb.rag;

import cn.dev33.satoken.stp.StpUtil;
import com.smartkb.common.Result;
import com.smartkb.kb.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "检索调试")
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;
    private final KnowledgeBaseService kbService;

    @Data
    public static class SearchReq {
        @NotNull
        private Long kbId;
        @NotBlank
        private String query;
    }

    @Operation(summary = "检索测试: 只跑检索流水线不生成回答, 返回各路得分(调试分块与召回质量)")
    @PostMapping("/search")
    public Result<RagService.RagResult> search(@RequestBody @jakarta.validation.Valid SearchReq req) {
        kbService.checkOwned(req.getKbId());
        return Result.ok(ragService.retrieve(StpUtil.getLoginIdAsLong(),
                req.getKbId(), req.getQuery(), null, false));
    }
}
