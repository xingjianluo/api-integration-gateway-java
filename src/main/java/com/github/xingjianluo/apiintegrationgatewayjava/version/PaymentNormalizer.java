package com.github.xingjianluo.apiintegrationgatewayjava.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.apiintegrationgatewayjava.model.NormalizedPayment;

public interface PaymentNormalizer {

    NormalizedPayment normalize(ApiVersion version, JsonNode raw);
}
