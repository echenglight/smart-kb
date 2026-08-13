package com.smartkb.common;

import lombok.Getter;

/** 业务异常: 由 GlobalExceptionHandler 统一转成 Result 返回 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(400, message);
    }
}
