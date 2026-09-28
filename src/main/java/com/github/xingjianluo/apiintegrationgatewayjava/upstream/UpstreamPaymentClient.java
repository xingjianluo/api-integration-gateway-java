package com.github.xingjianluo.apiintegrationgatewayjava.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.apiintegrationgatewayjava.version.ApiVersion;

import java.util.Map;

public interface UpstreamPaymentClient {

    JsonNode createPayment(ApiVersion version, Map<String, Object> payload, String scenario);
}
