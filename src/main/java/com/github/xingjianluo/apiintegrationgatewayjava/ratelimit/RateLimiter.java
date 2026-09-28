package com.github.xingjianluo.apiintegrationgatewayjava.ratelimit;

public interface RateLimiter {

    boolean tryAcquire(String clientKey);
}
