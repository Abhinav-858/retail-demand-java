package retail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ApiRouter — Clean REST API layer for the Retail Pipeline.
 * ==========================================================
 * Provides multiple focused endpoints with proper JSON responses,
 * CORS support, error handling, and a consistent response envelope.
 */
public class ApiRouter {

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    private final PipelineService pipelineService;

    public ApiRouter(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    // ── Endpoint Handlers ─────────────────────────────────────────────────────

    /** GET /api/health — Server health check. */
    public HttpHandler healthHandler() {
        return exchange -> {
            addCorsHeaders(exchange);
            if (handlePreflight(exchange))
                return;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "ok");
            data.put("database", pipelineService.isDbHealthy() ? "connected" : "disconnected");
            data.put("uptime", pipelineService.getUptimeFormatted());
            data.put("uptimeMs", pipelineService.getUptimeMs());
            data.put("timestamp", now());
            data.put("pipelineReady", pipelineService.hasCachedResult());

            sendSuccess(exchange, data);
        };
    }

    /** GET /api/config — Current pipeline configuration. */
    public HttpHandler configHandler() {
        return exchange -> {
            addCorsHeaders(exchange);
            if (handlePreflight(exchange))
                return;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("budget", pipelineService.getBudget());
            data.put("horizon", pipelineService.getHorizon());
            data.put("kMin", pipelineService.getKMin());
            data.put("kMax", pipelineService.getKMax());
            data.put("timestamp", now());

            sendSuccess(exchange, data);
        };
    }

    /** GET /api/pipeline — Full pipeline results (backward compatible). */
    public HttpHandler pipelineHandler() {
        return exchange -> {
            addCorsHeaders(exchange);
            if (handlePreflight(exchange))
                return;

            try {
                Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
                PipelineService.PipelineResult result = pipelineService.run(false, params);
                sendSuccess(exchange, resultToMap(result));
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        };
    }

    /** POST/GET /api/pipeline/run — Force re-run pipeline with optional params. */
    public HttpHandler pipelineRunHandler() {
        return exchange -> {
            addCorsHeaders(exchange);
            if (handlePreflight(exchange))
                return;

            try {
                Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
                PipelineService.PipelineResult result = pipelineService.run(true, params);
                sendSuccess(exchange, resultToMap(result));
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        };
    }

    /** GET /api/clusters — Cluster summary only. */
    public HttpHandler clustersHandler() {
        return exchange -> {
            addCorsHeaders(exchange);
            if (handlePreflight(exchange))
                return;

            try {
                PipelineService.PipelineResult r = pipelineService.run();
                sendSuccess(exchange, r.clusterSummary);
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        };
    }

    /** GET /api/forecast — Forecast data only. */
    public HttpHandler forecastHandler() {
        return exchange -> {
            addCorsHeaders(exchange);
            if (handlePreflight(exchange))
                return;

            try {
                PipelineService.PipelineResult r = pipelineService.run();
                sendSuccess(exchange, r.forecasts);
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        };
    }

    /** GET /api/allocation — Allocation data only. */
    public HttpHandler allocationHandler() {
        return exchange -> {
            addCorsHeaders(exchange);
            if (handlePreflight(exchange))
                return;

            try {
                PipelineService.PipelineResult r = pipelineService.run();
                sendSuccess(exchange, r.allocation);
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        };
    }

    /** GET /api/accuracy — Accuracy metrics only. */
    public HttpHandler accuracyHandler() {
        return exchange -> {
            addCorsHeaders(exchange);
            if (handlePreflight(exchange))
                return;

            try {
                PipelineService.PipelineResult r = pipelineService.run();
                sendSuccess(exchange, r.accuracy);
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        };
    }

    // ── Response Helpers ──────────────────────────────────────────────────────

    /** Converts PipelineResult to a flat map for backward-compatible JSON. */
    private Map<String, Object> resultToMap(PipelineService.PipelineResult r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("clusterSummary", r.clusterSummary);
        map.put("forecasts", r.forecasts);
        map.put("accuracy", r.accuracy);
        map.put("allocation", r.allocation);
        map.put("totalCost", r.totalCost);
        map.put("feasible", r.feasible);
        map.put("totalBudget", r.totalBudget);
        map.put("runTimestamp", r.runTimestamp);
        map.put("config", r.config);
        return map;
    }

    private void sendSuccess(HttpExchange exchange, Object data) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", true);
        envelope.put("data", data);
        sendJson(exchange, 200, envelope);
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", false);
        envelope.put("error", message);
        sendJson(exchange, code, envelope);
    }

    private void sendJson(HttpExchange exchange, int status, Object obj) throws IOException {
        String json = GSON.toJson(obj);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ── CORS ──────────────────────────────────────────────────────────────────

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    /** Handles OPTIONS preflight; returns true if handled. */
    private boolean handlePreflight(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Parse query string into key→value map. */
    static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty())
            return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2)
                params.put(kv[0], kv[1]);
        }
        return params;
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
