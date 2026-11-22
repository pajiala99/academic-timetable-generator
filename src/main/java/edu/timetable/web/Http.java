package edu.timetable.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import edu.timetable.dao.DataAccessException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A minimal JSON router and static-file server on top of the JDK's built-in HTTP server. */
final class Http {
    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Http() {
    }

    /** An error the client should see, with an HTTP status and a plain-language message. */
    static final class ApiError extends RuntimeException {
        final int status;

        ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** A file the browser should download rather than display. */
    record Download(byte[] bytes, String contentType, String fileName) {
    }

    record Request(String method, Matcher path, Map<String, String> query, byte[] body) {
        <T> T body(Class<T> type) {
            try {
                return JSON.readValue(body, type);
            } catch (IOException e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                throw new ApiError(400, root instanceof IllegalArgumentException ? root.getMessage() : "The request was not valid JSON.");
            }
        }

        int id() {
            return Integer.parseInt(path.group(1));
        }

        String query(String name, String fallback) {
            return query.getOrDefault(name, fallback);
        }
    }

    @FunctionalInterface
    interface Handler {
        Object handle(Request request) throws Exception;
    }

    private record Route(String method, Pattern pattern, Handler handler) {
    }

    /** Dispatches /api/... requests to handlers and turns their results (or errors) into JSON. */
    static final class Router implements HttpHandler {
        private final List<Route> routes = new ArrayList<>();
        private final Function<DataAccessException, String> constraintMessage;

        Router(Function<DataAccessException, String> constraintMessage) {
            this.constraintMessage = constraintMessage;
        }

        Router on(String method, String regex, Handler handler) {
            routes.add(new Route(method, Pattern.compile(regex), handler));
            return this;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            try (ex) {
                String path = ex.getRequestURI().getPath();
                Matcher matched = null;
                Route route = null;
                boolean pathKnown = false;
                for (Route r : routes) {
                    Matcher m = r.pattern().matcher(path);
                    if (!m.matches()) continue;
                    pathKnown = true;
                    if (r.method().equals(ex.getRequestMethod())) {
                        route = r;
                        matched = m;
                        break;
                    }
                }
                if (route == null) {
                    sendJson(ex, pathKnown ? 405 : 404, Map.of("error", pathKnown ? "Method not allowed" : "Not found: " + path));
                    return;
                }
                byte[] body;
                try (InputStream in = ex.getRequestBody()) {
                    body = in.readAllBytes();
                }
                Object result;
                try {
                    result = route.handler().handle(new Request(ex.getRequestMethod(), matched, query(ex), body));
                } catch (ApiError e) {
                    sendJson(ex, e.status, Map.of("error", e.getMessage()));
                    return;
                } catch (DataAccessException e) {
                    if (e.isConstraintViolation()) sendJson(ex, 409, Map.of("error", constraintMessage.apply(e)));
                    else sendJson(ex, 500, Map.of("error", "Database error: " + e.getMessage()));
                    return;
                } catch (IllegalArgumentException e) {
                    sendJson(ex, 400, Map.of("error", e.getMessage()));
                    return;
                } catch (Exception e) {
                    sendJson(ex, 500, Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
                    return;
                }
                if (result instanceof Download d) {
                    ex.getResponseHeaders().set("Content-Type", d.contentType());
                    ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + d.fileName() + "\"");
                    send(ex, 200, d.bytes());
                } else if (result == null) {
                    ex.sendResponseHeaders(204, -1);
                } else {
                    sendJson(ex, 200, result);
                }
            }
        }

        private static Map<String, String> query(HttpExchange ex) {
            Map<String, String> out = new HashMap<>();
            String raw = ex.getRequestURI().getRawQuery();
            if (raw == null) return out;
            for (String part : raw.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) out.put(decode(part.substring(0, eq)), decode(part.substring(eq + 1)));
            }
            return out;
        }

        private static String decode(String s) {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        }
    }

    /** Serves the single-page web app from the classpath folder /web. */
    static final class StaticFiles implements HttpHandler {
        private static final Map<String, String> TYPES = Map.of(
                "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
                "js", "text/javascript; charset=utf-8", "svg", "image/svg+xml", "png", "image/png", "ico", "image/x-icon");

        @Override
        public void handle(HttpExchange ex) throws IOException {
            try (ex) {
                String path = ex.getRequestURI().getPath();
                if (path.equals("/") || path.isEmpty()) path = "/index.html";
                if (path.contains("..") || !ex.getRequestMethod().equals("GET")) {
                    send(ex, 404, "Not found".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                try (InputStream in = Http.class.getResourceAsStream("/web" + path)) {
                    if (in == null) {
                        send(ex, 404, "Not found".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    String ext = path.substring(path.lastIndexOf('.') + 1);
                    ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
                    ex.getResponseHeaders().set("Cache-Control", "no-cache");
                    send(ex, 200, in.readAllBytes());
                }
            }
        }
    }

    static void sendJson(HttpExchange ex, int status, Object value) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(ex, status, JSON.writeValueAsBytes(value));
    }

    private static void send(HttpExchange ex, int status, byte[] bytes) throws IOException {
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
