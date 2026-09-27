package com.evops.apartment.esign;

/**
 * 电子签章平台异常：网络/超时/平台业务拒绝等。任务步骤遇此异常记 FAILED 断点，
 * 平台侧接口按业务幂等号设计，断点重跑不会产生重复单据。
 */
public class ESignException extends RuntimeException {
    public ESignException(String message) {
        super(message);
    }

    public ESignException(String message, Throwable cause) {
        super(message, cause);
    }
}
