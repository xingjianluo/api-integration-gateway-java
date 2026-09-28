package com.github.xingjianluo.apiintegrationgatewayjava.idempotency;

import java.util.function.Supplier;

public interface IdempotencyStore {

    <T> IdempotentResult<T> execute(String key, String requestHash, Supplier<T> action);
}
