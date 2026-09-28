package com.github.xingjianluo.apiintegrationgatewayjava.version;

import java.util.Arrays;
import java.util.Optional;

public enum ApiVersion {
    V1("v1"),
    V2("v2"),
    V3("v3");

    private final String code;

    ApiVersion(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<ApiVersion> fromCode(String code) {
        return Arrays.stream(values()).filter(v -> v.code.equals(code)).findFirst();
    }
}
