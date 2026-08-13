package com.smartkb.chat;

import com.smartkb.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "会话管理")
@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @Data
    public static class CreateReq {
        private Long kbId;
    }

    @Operation(summary = "我的会话列表")
    @GetMapping
    public Result<List<ConversationEntity>> list() {
        return Result.ok(conversationService.listMine());
    }

    @Operation(summary = "新建会话(绑定知识库)")
    @PostMapping
    public Result<ConversationEntity> create(@RequestBody CreateReq req) {
        return Result.ok(conversationService.create(req.getKbId()));
    }

    @Operation(summary = "会话消息记录(含引用回放)")
    @GetMapping("/{id}/messages")
    public Result<List<ChatMessageEntity>> messages(@PathVariable Long id) {
        return Result.ok(conversationService.messages(id));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        conversationService.delete(id);
        return Result.ok();
    }
}
