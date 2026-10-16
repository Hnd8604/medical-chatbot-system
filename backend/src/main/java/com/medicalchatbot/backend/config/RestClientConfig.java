package com.medicalchatbot.backend.config;

import com.medicalchatbot.backend.config.properties.ChatbotServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /** Built from Boot's {@link RestClient.Builder} so it shares the application's Jackson setup. */
    @Bean
    RestClient chatbotRestClient(RestClient.Builder builder, ChatbotServiceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    RestClient telegramRestClient(RestClient.Builder builder) {
        return builder.baseUrl("https://api.telegram.org").build();
    }
}
