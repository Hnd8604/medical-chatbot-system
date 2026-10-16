package com.medicalchatbot.backend.config;

import com.medicalchatbot.backend.config.properties.ChatbotServiceProperties;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Built from Boot's {@link RestClient.Builder} so it shares the application's Jackson setup.
     * Every call carries the id of the request that caused it ({@link RequestIdFilter}).
     */
    @Bean
    RestClient chatbotRestClient(RestClient.Builder builder, ChatbotServiceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor(forwardRequestId())
                .build();
    }

    static ClientHttpRequestInterceptor forwardRequestId() {
        return (request, body, execution) -> {
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            if (requestId != null) {
                request.getHeaders().set(RequestIdFilter.HEADER, requestId);
            }
            return execution.execute(request, body);
        };
    }

    @Bean
    RestClient telegramRestClient(RestClient.Builder builder) {
        return builder.baseUrl("https://api.telegram.org").build();
    }
}
