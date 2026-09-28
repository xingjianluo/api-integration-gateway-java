package com.github.xingjianluo.apiintegrationgatewayjava.service;

public interface PaymentGatewayService {

    PaymentResponse createPayment(PaymentRequest request, String clientKey);
}
