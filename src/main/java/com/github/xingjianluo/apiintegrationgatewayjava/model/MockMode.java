package com.github.xingjianluo.apiintegrationgatewayjava.model;

/**
 * 模拟上游的故障注入模式。
 */
public enum MockMode {
    /** 正常返回各版本的标准报文。 */
    NORMAL,
    /** 响应时间超过调用方读超时。 */
    TIMEOUT,
    /** 返回 HTTP 503。 */
    ERROR_503,
    /** 返回 HTTP 500。 */
    ERROR_500,
    /** 处理过程中直接抛出内部异常。 */
    INTERNAL_ERROR,
    /** 返回 HTTP 404（确定性、不重试）。 */
    NOT_FOUND,
    /** 缺少归一所需的必填字段。 */
    MISSING_FIELD,
    /** 字段类型/取值与归一契约不符。 */
    TYPE_MISMATCH
}
