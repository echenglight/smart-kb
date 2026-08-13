package com.smartkb.doc;

import com.smartkb.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Tag(name = "文档管理")
@RestController
@RequestMapping("/api/doc")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "上传文档(异步索引, 立即返回, 前端轮询状态)")
    @PostMapping("/upload")
    public Result<KbDocumentEntity> upload(@RequestParam Long kbId,
                                           @RequestParam("file") MultipartFile file) {
        return Result.ok(documentService.upload(kbId, file));
    }

    @Operation(summary = "知识库下的文档列表")
    @GetMapping
    public Result<List<KbDocumentEntity>> list(@RequestParam Long kbId) {
        return Result.ok(documentService.list(kbId));
    }

    @Operation(summary = "文档详情(轮询索引状态用)")
    @GetMapping("/{id}")
    public Result<KbDocumentEntity> get(@PathVariable Long id) {
        return Result.ok(documentService.get(id));
    }

    @Operation(summary = "文档分块列表(查看分块质量)")
    @GetMapping("/{id}/chunks")
    public Result<List<Map<String, Object>>> chunks(@PathVariable Long id) {
        return Result.ok(documentService.chunks(id));
    }

    @Operation(summary = "重建索引(调整分块参数后重跑)")
    @PostMapping("/{id}/reindex")
    public Result<KbDocumentEntity> reindex(@PathVariable Long id) {
        return Result.ok(documentService.reindex(id));
    }

    @Operation(summary = "删除文档")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.ok();
    }
}
