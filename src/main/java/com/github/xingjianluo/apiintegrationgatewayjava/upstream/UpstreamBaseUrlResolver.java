package com.github.xingjianluo.apiintegrationgatewayjava.upstream;

import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 解析上游基址。默认配置 http://localhost:${server.port}/mock-upstream 中 server.port=0，
 * 配置占位符只会得到字面值 0，因此在 Web 容器启动后用实际监听端口替换。
 * 在非 Web（MOCK）测试环境下没有真实端口，退化为占位符解析结果即可。
 */
@Component
public class UpstreamBaseUrlResolver {

    private final GatewayProperties properties;
    private final Environment environment;
    private final ObjectProvider<ServletWebServerApplicationContext> contextProvider;

    public UpstreamBaseUrlResolver(GatewayProperties properties,
                                   Environment environment,
                                   ObjectProvider<ServletWebServerApplicationContext> contextProvider) {
        this.properties = properties;
        this.environment = environment;
        this.contextProvider = contextProvider;
    }

    public String resolve() {
        String resolved = environment.resolvePlaceholders(properties.getUpstream().getBaseUrl());
        if ((resolved.contains(":0/") || resolved.endsWith(":0"))) {
            ServletWebServerApplicationContext context = contextProvider.getIfAvailable();
            if (context != null) {
                int port = context.getWebServer().getPort();
                if (port > 0) {
                    resolved = resolved.replace(":0", ":" + port);
                }
            }
        }
        return resolved;
    }
}
