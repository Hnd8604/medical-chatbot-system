package com.medicalchatbot.backend.config;

import com.medicalchatbot.backend.dto.response.ApiErrorResponse;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps every JSON body returned by an application controller in {@link ApiResponse}. Files,
 * streams and error bodies are written as-is.
 */
@RestControllerAdvice(basePackages = "com.medicalchatbot.backend.controller")
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        if (body instanceof ApiResponse<?> || body instanceof ApiErrorResponse) {
            return body;
        }
        if (body instanceof byte[] || body instanceof Resource || MediaType.TEXT_EVENT_STREAM.includes(selectedContentType)) {
            return body;
        }
        if (isBodyless(response)) {
            return null;
        }
        return ApiResponse.success(body);
    }

    /** Spring still consults the advice for an empty {@code ResponseEntity}; 204/304 must stay empty. */
    private static boolean isBodyless(ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse servletResponse) {
            int status = servletResponse.getServletResponse().getStatus();
            return status == HttpStatus.NO_CONTENT.value() || status == HttpStatus.NOT_MODIFIED.value();
        }
        return false;
    }
}
