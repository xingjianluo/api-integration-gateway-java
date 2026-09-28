package com.github.xingjianluo.apiintegrationgatewayjava.resilience;

import java.util.function.Supplier;

public interface RetryExecutor {

    <T> T execute(Supplier<T> action);
}
