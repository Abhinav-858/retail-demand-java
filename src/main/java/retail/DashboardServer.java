package retail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;

/**
 * DashboardServer — HTTP Server for the Retail Pipeline Dashboard
 * ================================================================
 * Serves static files from /public and registers REST API endpoints
 * via {@link ApiRouter} backed by {@link PipelineService}.
 */
public class DashboardServer {

    private static final int PORT = 8080;
    private static final String PUBLIC_DIR = "public";

    public static void main(String[] args) throws IOException {
        // Ensure public directory exists
        File publicFolder = new File(PUBLIC_DIR);
        if (!publicFolder.exists())
            publicFolder.mkdirs();

        // Create shared service and router
        PipelineService service = new PipelineService();
        ApiRouter router = new ApiRouter(service);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // ── API Endpoints ─────────────────────────────────────────────────
        server.createContext("/api/health", router.healthHandler());
        server.createContext("/api/config", router.configHandler());
        server.createContext("/api/pipeline/run", router.pipelineRunHandler());
        server.createContext("/api/pipeline", router.pipelineHandler());
        server.createContext("/api/clusters", router.clustersHandler());
        server.createContext("/api/forecast", router.forecastHandler());
        server.createContext("/api/allocation", router.allocationHandler());
        server.createContext("/api/accuracy", router.accuracyHandler());

        // ── Static File Server ────────────────────────────────────────────
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(null);
        server.start();

        ReportingModule.section("Dashboard Server Started on port " + PORT);
        System.out.println("Access the dashboard at: http://localhost:" + PORT);
        System.out.println("API endpoints:");
        System.out.println("  GET  /api/health          — Server health check");
        System.out.println("  GET  /api/pipeline        — Full pipeline results");
        System.out.println("  GET  /api/pipeline/run    — Force re-run (+ ?budget=X&horizon=Y)");
        System.out.println("  GET  /api/clusters        — Cluster summary");
        System.out.println("  GET  /api/forecast        — Forecast data");
        System.out.println("  GET  /api/allocation      — Allocation data");
        System.out.println("  GET  /api/accuracy        — Accuracy metrics");
        System.out.println("  GET  /api/config          — Current configuration");

        // Pre-warm the pipeline cache on startup
        System.out.println("\nPre-warming pipeline cache...");
        try {
            service.run();
            System.out.println("Pipeline cache ready.");
        } catch (Exception e) {
            System.err.println("Warning: Pipeline pre-warm failed: " + e.getMessage());
        }
    }

    // ── Static File Handler ───────────────────────────────────────────────────
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/"))
                path = "/index.html";

            File file = new File(PUBLIC_DIR, path);

            if (file.exists() && file.isFile()) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                String contentType = getContentType(path);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                String response = "404 Not Found";
                exchange.sendResponseHeaders(404, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }

        private String getContentType(String path) {
            if (path.endsWith(".html"))
                return "text/html; charset=UTF-8";
            if (path.endsWith(".css"))
                return "text/css; charset=UTF-8";
            if (path.endsWith(".js"))
                return "application/javascript; charset=UTF-8";
            if (path.endsWith(".json"))
                return "application/json; charset=UTF-8";
            if (path.endsWith(".svg"))
                return "image/svg+xml";
            if (path.endsWith(".png"))
                return "image/png";
            if (path.endsWith(".ico"))
                return "image/x-icon";
            if (path.endsWith(".woff"))
                return "font/woff";
            if (path.endsWith(".woff2"))
                return "font/woff2";
            return "text/plain; charset=UTF-8";
        }
    }
}
