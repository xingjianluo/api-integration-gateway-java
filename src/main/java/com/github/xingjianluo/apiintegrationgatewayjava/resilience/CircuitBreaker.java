package com.github.xingjianluo.apiintegrationgatewayjava.resilience;

public interface CircuitBreaker {

    void allowRequest();

    void recordSuccess();

    void recordFailure();
}
