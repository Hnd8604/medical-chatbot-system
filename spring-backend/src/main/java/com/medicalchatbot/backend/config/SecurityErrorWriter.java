package com.medicalchatbot.backend.config;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.response.ApiErrorResponse;
import com.medicalchatbot.backend.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, int status, String errorCode, String detail) throws IOException {
        ErrorCode resolved = ErrorCode.resolve(errorCode, HttpStatusCode.valueOf(status));
        ApiErrorResponse body = ApiErrorResponse.from(resolved, status, detail);

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
