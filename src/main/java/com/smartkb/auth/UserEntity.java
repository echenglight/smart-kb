package com.smartkb.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** SHA-256(salt + 明文), 永不明文落库 */
    @JsonIgnore
    private String password;

    @JsonIgnore
    private String salt;

    private String nickname;

    private LocalDateTime createdAt;
}
