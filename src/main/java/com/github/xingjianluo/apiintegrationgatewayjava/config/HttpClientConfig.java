package com.github.xingjianluo.apiintegrationgatewayjava.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient upstreamRestClient(GatewayProperties properties) {
        // 显式使用 JDK HttpClient，避免 SimpleClientHttpRequestFactory(HttpURLConnection)
        // 在非 JSON / 出错响应后复用陈旧 keep-alive 连接导致的偶发读超时。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getUpstream().getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getUpstream().getReadTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
