package com.github.xingjianluo.apiintegrationgatewayjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 注册配置属性与调用模拟上游使用的 RestClient。
 * baseUrl 指向本地模拟上游（同一应用，测试用随机端口，通过配置注入）。
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class RestClientConfig {

    @Bean
    public RestClient upstreamRestClient(GatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(properties.getUpstream().getReadTimeout());
        factory.setConnectTimeout(java.time.Duration.ofSeconds(2));
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        String baseUrl = properties.getUpstream().getBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl.trim());
        }
        return builder.build();
    }
}
