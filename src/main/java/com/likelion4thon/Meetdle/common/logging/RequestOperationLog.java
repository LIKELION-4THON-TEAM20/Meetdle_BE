package com.likelion4thon.Meetdle.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestOperationLog extends OncePerRequestFilter {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String REQUEST_ID = "requestId";
    private static final String RESULT_CODE = RequestOperationLog.class.getName() + ".resultCode";
    private static final String FAILURE_TYPE = RequestOperationLog.class.getName() + ".failureType";
    private static final String LOGOUT = "/api/auth/logout";
    private static final Logger log = LoggerFactory.getLogger(RequestOperationLog.class);

    public static void setResultCode(HttpServletRequest request, String code) {
        request.setAttribute(RESULT_CODE, code);
    }

    public static String currentRequestId() {
        String requestId = MDC.get(REQUEST_ID);
        return requestId == null ? "-" : requestId;
    }

    public static void setFailureType(HttpServletRequest request, Throwable failure) {
        request.setAttribute(FAILURE_TYPE, failure.getClass().getSimpleName());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        String previousRequestId = MDC.get(REQUEST_ID);
        long started = System.nanoTime();
        String failureType = "-";
        MDC.put(REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            failureType = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            try {
                int status = response.getStatus();
                if (!"-".equals(failureType) && status < 400) {
                    status = 500;
                }
                Object handledFailure = request.getAttribute(FAILURE_TYPE);
                logRequest(request, status, requestId, started,
                        "-".equals(failureType) && handledFailure instanceof String type ? type : failureType);
            } finally {
                if (previousRequestId == null) {
                    MDC.remove(REQUEST_ID);
                } else {
                    MDC.put(REQUEST_ID, previousRequestId);
                }
            }
        }
    }

    private static void logRequest(HttpServletRequest request, int status, String requestId, long started,
                                   String failureType) {
        String route = route(request);
        Object code = request.getAttribute(RESULT_CODE);
        var event = log.atDebug();
        if (status >= 500 || "PROVIDER_UNAVAILABLE".equals(code)) {
            event = log.atWarn();
        } else if (status >= 400 || route.startsWith("/api/auth/") || isWrite(request.getMethod())) {
            event = log.atInfo();
        }
        event.log("api_request requestId={} method={} route={} roomId={} status={} code={} durationMs={} failureType={}",
                requestId, request.getMethod(), route, roomId(request), status, code == null ? "-" : code,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), failureType);
    }

    private static boolean isWrite(String method) {
        return switch (method) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String route) {
            return route;
        }
        return request.getRequestURI().equals(request.getContextPath() + LOGOUT) ? LOGOUT : "unmapped";
    }

    private static String roomId(HttpServletRequest request) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables
                && variables.get("roomId") instanceof String value && value.length() == 36) {
            try {
                return UUID.fromString(value).toString();
            } catch (IllegalArgumentException invalidRoomId) {
                return "-";
            }
        }
        return "-";
    }
}
