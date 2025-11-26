package com.jaf.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

public final class FuzzingRequestContext {
    private static final String HEADER_NAME = "X-Fuzzing-Request-Id";
    private static final ThreadLocal<String> CURRENT_REQUEST_ID = new ThreadLocal<>();

    private FuzzingRequestContext() {}

    public static void updateFromServletRequest(Object request) {
        String requestId = extractHeaderValue(request);
        if (requestId == null || requestId.isEmpty()) {
            requestId = newRandomRequestId();
        }
        CURRENT_REQUEST_ID.set(requestId);
    }

    public static String currentRequestId() {
        String requestId = CURRENT_REQUEST_ID.get();
        if (requestId == null || requestId.isEmpty()) {
            requestId = newRandomRequestId();
            CURRENT_REQUEST_ID.set(requestId);
        }
        return requestId;
    }

    private static String extractHeaderValue(Object request) {
        if (request == null) {
            return null;
        }
        try {
            Method getHeaderMethod = request.getClass().getMethod("getHeader", String.class);
            Object value = getHeaderMethod.invoke(request, HEADER_NAME);
            if (value instanceof String) {
                String headerValue = ((String) value).trim();
                return headerValue.isEmpty() ? null : headerValue;
            }
        } catch (NoSuchMethodException ignored) {
            // Fall through to generate a random request ID when the method is absent.
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            // Fall through to fallback generation if reflection fails.
        }
        return null;
    }

    private static String newRandomRequestId() {
        return UUID.randomUUID().toString();
    }
}
