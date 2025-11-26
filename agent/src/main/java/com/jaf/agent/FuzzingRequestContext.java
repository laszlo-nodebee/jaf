package com.jaf.agent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FuzzingRequestContext {
    private static final String HEADER_NAME = "X-Fuzzing-Request-Id";
    private static final String REQUEST_STATE_KEY =
            FuzzingRequestContext.class.getName() + ".state";
    private static final String ASYNC_LISTENER_KEY =
            FuzzingRequestContext.class.getName() + ".async-listener";

    private static final ThreadLocal<RequestState> CURRENT_STATE = new ThreadLocal<>();
    private static volatile RequestFinishedListener requestFinishedListener;

    private FuzzingRequestContext() {}

    public static void updateFromServletRequest(Object request) {
        RequestState state = stateFromRequest(request);
        if (state == null) {
            String requestId = extractHeaderValue(request);
            if (requestId == null || requestId.isEmpty()) {
                requestId = newRandomRequestId();
            }
            state = new RequestState(requestId);
            storeStateOnRequest(request, state);
        }
        CURRENT_STATE.set(state);
    }

    public static void requestFinished(Object request) {
        RequestState state = stateFromRequest(request);
        if (state == null) {
            state = CURRENT_STATE.get();
        }
        if (state == null) {
            CURRENT_STATE.remove();
            return;
        }
        if (handleAsyncIfNeeded(request, state)) {
            CURRENT_STATE.remove();
            return;
        }
        completeRequest(request, state);
    }

    public static void markNewCoverageObserved() {
        RequestState state = CURRENT_STATE.get();
        if (state != null) {
            state.markCoverage();
        }
    }

    public static String currentRequestId() {
        RequestState state = CURRENT_STATE.get();
        if (state != null && state.requestId != null && !state.requestId.isEmpty()) {
            return state.requestId;
        }
        String requestId = newRandomRequestId();
        CURRENT_STATE.set(new RequestState(requestId));
        return requestId;
    }

    public static void registerRequestFinishedListener(RequestFinishedListener listener) {
        requestFinishedListener = listener;
    }

    private static void completeRequest(Object request, RequestState state) {
        if (!state.markCompleted()) {
            return;
        }
        removeStateFromRequest(request);
        CURRENT_STATE.remove();
        RequestFinishedListener listener = requestFinishedListener;
        if (listener != null) {
            try {
                listener.onRequestFinished(state.requestId, state.hasNewCoverage());
            } catch (RuntimeException e) {
                System.err.println("Request completion listener failed: " + e.getMessage());
            }
        }
    }

    private static boolean handleAsyncIfNeeded(Object request, RequestState state) {
        Boolean asyncStarted = asBoolean(invokeNoArg(request, "isAsyncStarted"));
        if (asyncStarted == null || !asyncStarted.booleanValue()) {
            return false;
        }
        Object existingListener = getRequestAttribute(request, ASYNC_LISTENER_KEY);
        if (existingListener != null) {
            return true;
        }

        Object asyncContext = invokeNoArg(request, "getAsyncContext");
        if (asyncContext == null) {
            return false;
        }

        Class<?> listenerType =
                findFirstClass(
                        request.getClass().getClassLoader(),
                        "jakarta.servlet.AsyncListener",
                        "javax.servlet.AsyncListener");
        if (listenerType == null) {
            return false;
        }

        InvocationHandler handler =
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("onStartAsync".equals(name) && args != null && args.length > 0) {
                        Object event = args[0];
                        Object context = invokeNoArg(event, "getAsyncContext");
                        if (context != null) {
                            try {
                                Method addListener =
                                        context.getClass().getMethod("addListener", listenerType);
                                addListener.invoke(context, proxy);
                            } catch (Exception ignored) {
                                // best effort re-registration
                            }
                        }
                        return null;
                    }
                    if ("onComplete".equals(name)
                            || "onTimeout".equals(name)
                            || "onError".equals(name)) {
                        completeRequest(request, state);
                        return null;
                    }
                    return null;
                };

        ClassLoader proxyLoader = listenerType.getClassLoader();
        if (proxyLoader == null) {
            proxyLoader = FuzzingRequestContext.class.getClassLoader();
        }
        Object listener =
                Proxy.newProxyInstance(
                        proxyLoader,
                        new Class<?>[] {listenerType},
                        handler);
        try {
            Method addListener = asyncContext.getClass().getMethod("addListener", listenerType);
            addListener.invoke(asyncContext, listener);
            setRequestAttribute(request, ASYNC_LISTENER_KEY, listener);
        } catch (Exception ignored) {
            return false;
        }
        return true;
    }

    private static RequestState stateFromRequest(Object request) {
        Object attribute = getRequestAttribute(request, REQUEST_STATE_KEY);
        if (attribute instanceof RequestState) {
            return (RequestState) attribute;
        }
        return null;
    }

    private static void storeStateOnRequest(Object request, RequestState state) {
        setRequestAttribute(request, REQUEST_STATE_KEY, state);
    }

    private static void removeStateFromRequest(Object request) {
        removeRequestAttribute(request, REQUEST_STATE_KEY);
        removeRequestAttribute(request, ASYNC_LISTENER_KEY);
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

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private static Object getRequestAttribute(Object request, String key) {
        if (request == null || key == null) {
            return null;
        }
        try {
            Method getAttribute = request.getClass().getMethod("getAttribute", String.class);
            getAttribute.setAccessible(true);
            return getAttribute.invoke(request, key);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private static void setRequestAttribute(Object request, String key, Object value) {
        if (request == null || key == null) {
            return;
        }
        try {
            Method setAttribute =
                    request.getClass().getMethod("setAttribute", String.class, Object.class);
            setAttribute.setAccessible(true);
            setAttribute.invoke(request, key, value);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
        }
    }

    private static void removeRequestAttribute(Object request, String key) {
        if (request == null || key == null) {
            return;
        }
        try {
            Method removeAttribute = request.getClass().getMethod("removeAttribute", String.class);
            removeAttribute.setAccessible(true);
            removeAttribute.invoke(request, key);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            // Fallback to setting null when removeAttribute is not available.
            setRequestAttribute(request, key, null);
        }
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return null;
    }

    private static Class<?> findFirstClass(ClassLoader loader, String... classNames) {
        ClassLoader[] candidates = {
            loader,
            FuzzingRequestContext.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (String className : classNames) {
            for (ClassLoader candidate : candidates) {
                try {
                    if (candidate != null) {
                        return Class.forName(className, false, candidate);
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }
        }
        return null;
    }

    static final class RequestState {
        private final String requestId;
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private volatile boolean hasNewCoverage;

        RequestState(String requestId) {
            this.requestId = requestId;
        }

        void markCoverage() {
            hasNewCoverage = true;
        }

        boolean hasNewCoverage() {
            return hasNewCoverage;
        }

        boolean markCompleted() {
            return completed.compareAndSet(false, true);
        }
    }

    @FunctionalInterface
    public interface RequestFinishedListener {
        void onRequestFinished(String requestId, boolean hasNewCoverage);
    }
}
