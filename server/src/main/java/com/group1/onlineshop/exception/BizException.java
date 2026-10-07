package com.group1.onlineshop.exception;

/** 业务异常：抛出的 message 会直接作为接口返回的 error 字段。 */
public class BizException extends RuntimeException {

    private final int status;

    public BizException(String message) {
        this(message, 400);
    }

    public BizException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
