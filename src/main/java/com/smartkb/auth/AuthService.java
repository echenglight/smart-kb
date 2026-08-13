package com.smartkb.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartkb.auth.mapper.UserMapper;
import com.smartkb.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * 注册登录
 *
 * 密码存储: 随机盐 + SHA-256(salt + 明文)。
 * 加盐可防止彩虹表攻击，并使相同密码在不同用户下产生不同散列值。
 * 生产可进一步换 BCrypt(自带盐 + 慢哈希抗暴力破解)。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private static final SecureRandom RANDOM = new SecureRandom();

    public void register(String username, String password, String nickname) {
        if (username == null || username.isBlank() || password == null || password.length() < 6) {
            throw new BizException("用户名不能为空, 密码至少 6 位");
        }
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
        if (count > 0) {
            throw new BizException("用户名已存在");
        }
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setSalt(randomSalt());
        user.setPassword(sha256(user.getSalt() + password));
        user.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
    }

    public Map<String, Object> login(String username, String password) {
        UserEntity user = userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
        if (user == null || !user.getPassword().equals(sha256(user.getSalt() + password))) {
            throw new BizException(401, "用户名或密码错误");
        }
        StpUtil.login(user.getId());
        return Map.of("token", StpUtil.getTokenValue(), "user", user);
    }

    public UserEntity currentUser() {
        return userMapper.selectById(StpUtil.getLoginIdAsLong());
    }

    private String randomSalt() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException(500, "摘要计算失败");
        }
    }
}
