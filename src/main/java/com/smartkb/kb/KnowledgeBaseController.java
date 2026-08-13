package com.smartkb.kb;

import com.smartkb.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "知识库管理")
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;

    @Data
    public static class KbReq {
        private String name;
        private String description;
        private Integer chunkSize;
        private Integer chunkOverlap;
    }

    @Operation(summary = "我的知识库列表(含文档/分块统计)")
    @GetMapping
    public Result<List<KnowledgeBaseEntity>> list() {
        return Result.ok(kbService.listMine());
    }

    @Operation(summary = "创建知识库")
    @PostMapping
    public Result<KnowledgeBaseEntity> create(@RequestBody KbReq req) {
        return Result.ok(kbService.create(req.getName(), req.getDescription(),
                req.getChunkSize(), req.getChunkOverlap()));
    }

    @Operation(summary = "更新知识库")
    @PutMapping("/{id}")
    public Result<KnowledgeBaseEntity> update(@PathVariable Long id, @RequestBody KbReq req) {
        return Result.ok(kbService.update(id, req.getName(), req.getDescription(),
                req.getChunkSize(), req.getChunkOverlap()));
    }

    @Operation(summary = "删除知识库(级联删除文档与分块)")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        kbService.delete(id);
        return Result.ok();
    }
}
