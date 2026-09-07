package org.panini.system;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

/**
 * Системный класс для логирования информации о входящих запросов и исходящих ответов сервиса.
 * @author Alina.Doronina
 */
@Slf4j
@Component
public class Logger extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, 0);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        try {
            byte[] requestBody = requestWrapper.getContentAsByteArray();

            log.info("Request: {} {}", request.getMethod(), request.getRequestURI());
            log.info("Request query parameters: {}", request.getQueryString());
            log.info("Request body: {}", byteArrayToStringJson(requestBody, request.getCharacterEncoding()));

            filterChain.doFilter(requestWrapper, responseWrapper);

            byte[] responseBody = responseWrapper.getContentAsByteArray();
            log.info("Response: {} - {}",
                    response.getStatus(),
                    byteArrayToStringJson(responseBody, response.getCharacterEncoding())
            );
        } finally {
            responseWrapper.copyBodyToResponse();
        }
    }

    private static String byteArrayToStringJson(
            byte[] byteArray,
            String charEncoding
    ) throws UnsupportedEncodingException {
        return byteArray.length > 0 ? new String(byteArray, charEncoding) : "{}";
    }
}