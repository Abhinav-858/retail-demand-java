package retail;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * PipelineService — Orchestrates the ML pipeline and caches results.
 * ===================================================================
 * Extracts pipeline logic from the monolithic API handler into a reusable,
 * thread-safe service with configurable parameters and result caching.
 */
public class PipelineService {

    // ── Configuration ─────────────────────────────────────────────────────────
    private double budget = 50_000.0;
    private int horizon = 30;
    private int seasonLen = 7;
    private int kMin = 2;
    private int kMax = 6;
    private int nSkus = 20;
    private int nCust = 50;
    private int nDays = 365;
    private long seed = 42L;

    // ── Cached Result ─────────────────────────────────────────────────────────
    private PipelineResult cachedResult = null;
    private final long startTime = System.currentTimeMillis();

    // ── Result Container ──────────────────────────────────────────────────────

    public static class PipelineResult {
        public List<Map<String, Object>> clusterSummary;
        public Map<String, Object> forecasts;
        public List<Map<String, Object>> accuracy;
        public List<Map<String, Object>> allocation;
        public double totalCost;
        public boolean feasible;
        public double totalBudget;
        public String runTimestamp;
        public Map<String, Object> config;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public double getBudget() {
        return budget;
    }

    public int getHorizon() {
        return horizon;
    }

    public int getKMin() {
        return kMin;
    }

    public int getKMax() {
        return kMax;
    }

    public long getUptimeMs() {
        return System.currentTimeMillis() - startTime;
    }

    public String getUptimeFormatted() {
        long ms = getUptimeMs();
        long secs = ms / 1000;
        long mins = secs / 60;
        long hrs = mins / 60;
        return String.format("%dh %dm %ds", hrs, mins % 60, secs % 60);
    }

    public boolean hasCachedResult() {
        return cachedResult != null;
    }

    public PipelineResult getCachedResult() {
        return cachedResult;
    }

    /** Returns true if the database is accessible. */
    public boolean isDbHealthy() {
        try (TransactionDB db = new TransactionDB()) {
            return db.isHealthy();
        } catch (Exception e) {
            return false;
        }
    }

    // ── Run Pipeline ──────────────────────────────────────────────────────────

    /**
     * Runs the full pipeline. If forceRun is false and a cached result exists,
     * returns the cache. Optional query params override configuration.
     */
    public synchronized PipelineResult run(boolean forceRun,
            Map<String, String> params) {

        // Apply optional parameter overrides
        if (params != null) {
            if (params.containsKey("budget"))
                budget = Double.parseDouble(params.get("budget"));
            if (params.containsKey("horizon"))
                horizon = Integer.parseInt(params.get("horizon"));
            if (params.containsKey("kMin"))
                kMin = Integer.parseInt(params.get("kMin"));
            if (params.containsKey("kMax"))
                kMax = Integer.parseInt(params.get("kMax"));
            // If any config changed, force a re-run
            if (!params.isEmpty())
                forceRun = true;
        }

        if (!forceRun && cachedResult != null) {
            return cachedResult;
        }

        try {
            System.out.println("[PipelineService] Running pipeline...");
            long t0 = System.currentTimeMillis();

            // ── Module 1: TransactionDB ───────────────────────────────────────
            TransactionDB db = new TransactionDB();
            db.seedSynthetic(nSkus, nCust, nDays, seed);
            List<SkuFeature> features = db.skuFeatureMatrix();

            // ── Module 2: Clustering Engine ───────────────────────────────────
            ClusteringEngine engine = new ClusteringEngine(features);
            int bestK = engine.selectK(kMin, kMax);
            engine.fitKMeans(bestK);
            engine.fitHierarchical(bestK);

            Map<Integer, List<String>> clusterMembers = engine.getClusterMembers(true);

            Map<Integer, TreeMap<LocalDate, Double>> clusterSeries = new TreeMap<>();
            for (Map.Entry<Integer, List<String>> entry : clusterMembers.entrySet()) {
                clusterSeries.put(entry.getKey(), db.clusterTimeSeries(entry.getValue()));
            }

            // ── Module 3: Forecasting ─────────────────────────────────────────
            ForecastModule forecaster = new ForecastModule(horizon, seasonLen);
            forecaster.fitAll(clusterSeries);

            Map<Integer, Double> avgCosts = engine.avgUnitCostPerCluster();
            Map<Integer, Double> forecastedDemand = forecaster.totalForecastedDemand();

            // ── Module 4: Inventory Optimization ──────────────────────────────
            InventoryOptimizer optimizer = new InventoryOptimizer(avgCosts, budget);
            InventoryOptimizer.AllocationResult alloc = optimizer.optimise(forecastedDemand);
            Map<Integer, ForecastModule.AccuracyMetrics> metrics = forecaster.getAllMetrics();

            db.close();

            // ── Build Result ──────────────────────────────────────────────────
            PipelineResult result = new PipelineResult();
            result.totalBudget = budget;
            result.totalCost = alloc.totalCost();
            result.feasible = alloc.feasible();
            result.runTimestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            // Config snapshot
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("budget", budget);
            cfg.put("horizon", horizon);
            cfg.put("seasonLen", seasonLen);
            cfg.put("kMin", kMin);
            cfg.put("kMax", kMax);
            cfg.put("bestK", bestK);
            cfg.put("nSkus", nSkus);
            cfg.put("nCustomers", nCust);
            cfg.put("nDays", nDays);
            result.config = cfg;

            // Cluster Summary
            result.clusterSummary = buildClusterSummary(features);

            // Forecasts
            result.forecasts = buildForecasts(forecaster, clusterSeries);

            // Accuracy
            result.accuracy = buildAccuracy(metrics);

            // Allocation
            result.allocation = buildAllocation(alloc, forecastedDemand, avgCosts);

            long elapsed = System.currentTimeMillis() - t0;
            System.out.printf("[PipelineService] Pipeline complete in %dms%n", elapsed);

            cachedResult = result;
            return result;

        } catch (Exception e) {
            System.err.println("[PipelineService] ERROR: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Pipeline execution failed: " + e.getMessage(), e);
        }
    }

    /** Convenience: run with defaults, use cache if available. */
    public PipelineResult run() {
        return run(false, null);
    }

    // ── Data Builders ─────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildClusterSummary(List<SkuFeature> features) {
        Map<Integer, List<SkuFeature>> groups = new TreeMap<>();
        for (SkuFeature f : features) {
            groups.computeIfAbsent(f.clusterKmeans, k -> new ArrayList<>()).add(f);
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<Integer, List<SkuFeature>> entry : groups.entrySet()) {
            int cid = entry.getKey();
            List<SkuFeature> g = entry.getValue();

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", cid);
            node.put("size", g.size());
            node.put("avgUnitCost", round2(g.stream().mapToDouble(f -> f.unitCost).average().orElse(0)));
            node.put("avgTotalQty", round2(g.stream().mapToDouble(f -> f.totalQty).average().orElse(0)));
            node.put("avgRevenue", round2(g.stream().mapToDouble(f -> f.totalRevenue).average().orElse(0)));
            node.put("avgUniqueCustomers", round2(g.stream().mapToDouble(f -> f.uniqueCustomers).average().orElse(0)));
            node.put("avgActiveDays", round2(g.stream().mapToDouble(f -> f.activeDays).average().orElse(0)));
            list.add(node);
        }
        return list;
    }

    private Map<String, Object> buildForecasts(ForecastModule forecaster,
            Map<Integer, TreeMap<LocalDate, Double>> clusterSeries) {
        Map<String, Object> map = new LinkedHashMap<>();

        for (Map.Entry<Integer, ForecastModule.ForecastResult> entry : forecaster.results.entrySet()) {
            int cid = entry.getKey();
            ForecastModule.ForecastResult fr = entry.getValue();
            TreeMap<LocalDate, Double> actuals = clusterSeries.get(cid);

            List<LocalDate> actDates = new ArrayList<>(actuals.keySet());
            int actSize = actDates.size();
            int actTake = Math.min(30, actSize);

            List<String> labels = new ArrayList<>();
            List<Double> actualData = new ArrayList<>();
            List<Double> forecastData = new ArrayList<>();

            // Fill actuals part
            for (int i = actSize - actTake; i < actSize; i++) {
                LocalDate d = actDates.get(i);
                labels.add(d.toString());
                actualData.add(round2(actuals.get(d)));
                forecastData.add(null);
            }

            // Link last actual point to forecast line
            if (actSize > 0) {
                LocalDate lastDate = actDates.get(actSize - 1);
                forecastData.set(forecastData.size() - 1, round2(actuals.get(lastDate)));
            }

            // Fill forecast part
            LocalDate fDate = fr.forecastStart();
            for (double val : fr.forecastValues()) {
                labels.add(fDate.toString());
                actualData.add(null);
                forecastData.add(round2(val));
                fDate = fDate.plusDays(1);
            }

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("labels", labels);
            node.put("actuals", actualData);
            node.put("forecasts", forecastData);
            node.put("forecastedTotal", round2(Arrays.stream(fr.forecastValues()).sum()));
            map.put(String.valueOf(cid), node);
        }
        return map;
    }

    private List<Map<String, Object>> buildAccuracy(
            Map<Integer, ForecastModule.AccuracyMetrics> metrics) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<Integer, ForecastModule.AccuracyMetrics> entry : metrics.entrySet()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", entry.getKey());
            node.put("mae", entry.getValue().mae());
            node.put("rmse", entry.getValue().rmse());
            node.put("smapePct", entry.getValue().smapePct());
            list.add(node);
        }
        return list;
    }

    private List<Map<String, Object>> buildAllocation(
            InventoryOptimizer.AllocationResult alloc,
            Map<Integer, Double> forecastedDemand,
            Map<Integer, Double> avgCosts) {

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : alloc.units().entrySet()) {
            int cid = entry.getKey();
            double demand = forecastedDemand.getOrDefault(cid, 0.0);
            double cost = avgCosts.getOrDefault(cid, 0.0);
            int units = entry.getValue();
            double coverage = demand < 1e-6 ? 0 : Math.min(units / demand, 1.0) * 100;
            double total = units * cost;

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", cid);
            node.put("demand", round2(demand));
            node.put("units", units);
            node.put("coverage", round2(coverage));
            node.put("totalCost", round2(total));
            list.add(node);
        }
        return list;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
