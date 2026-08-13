package com.smartkb.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.smartkb.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Data
    public static class AuthReq {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
        private String nickname;
    }

    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<Void> register(@RequestBody @jakarta.validation.Valid AuthReq req) {
        authService.register(req.getUsername(), req.getPassword(), req.getNickname());
        return Result.ok();
    }

    @Operation(summary = "登录, token 同时写入 Cookie")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody @jakarta.validation.Valid AuthReq req) {
        return Result.ok(authService.login(req.getUsername(), req.getPassword()));
    }

    @Operation(summary = "当前登录用户")
    @GetMapping("/me")
    public Result<UserEntity> me() {
        return Result.ok(authService.currentUser());
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.ok();
    }
}
