package com.github.xingjianluo.apiintegrationgatewayjava.model;

/**
 * 当前接入层兼容的上游接口版本。
 */
public enum ApiVersion {
    V1,
    V2,
    V3;

    /**
     * 解析版本字符串；无法识别时返回 null，由调用方按“版本不支持”拒绝。
     */
    public static ApiVersion fromHeader(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "v1", "1" -> V1;
            case "v2", "2" -> V2;
            case "v3", "3" -> V3;
            default -> null;
        };
    }
}
