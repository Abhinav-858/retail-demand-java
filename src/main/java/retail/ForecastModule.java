package retail;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.*;

/**
 * MODULE 3 — Forecast Module
 * ---------------------------
 * Fits time-series models per demand cluster and produces n-step ahead
 * forecasts together with accuracy metrics.
 *
 * Primary : Holt-Winters Triple Exponential Smoothing (built-in, no deps)
 * Optional : ARIMA / Prophet — add adapters by implementing ForecastStrategy
 *
 * Accuracy metric: sMAPE (Symmetric MAPE) — handles zero-demand days
 * gracefully.
 */
public class ForecastModule {

    // ── Result containers ──────────────────────────────────────────────────────

    public record ForecastResult(
            int clusterId,
            double[] forecastValues, // length = horizon
            LocalDate forecastStart, // first forecast date
            AccuracyMetrics metrics) {
    }

    public record AccuracyMetrics(double mae, double rmse, double smapePct) {
        @Override
        public String toString() {
            return String.format("MAE=%.2f  RMSE=%.2f  sMAPE=%.2f%%",
                    mae, rmse, smapePct);
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final int horizon;
    private final int seasonLen;

    public final Map<Integer, ForecastResult> results = new TreeMap<>();

    public ForecastModule(int horizon, int seasonLen) {
        this.horizon = horizon;
        this.seasonLen = seasonLen;
    }

    // ── Holt-Winters Triple Exponential Smoothing ─────────────────────────────

    /**
     * Fits Holt-Winters (additive) on the cluster time-series.
     *
     * @param clusterId cluster identifier
     * @param ts        ordered map of date → daily demand
     */
    public ForecastResult fitHoltWinters(
            int clusterId, TreeMap<LocalDate, Double> ts) {

        double[] values = ts.values().stream()
                .mapToDouble(Double::doubleValue).toArray();

        int splitIdx = (int) (values.length * 0.8);
        double[] train = Arrays.copyOf(values, splitIdx);
        double[] test = Arrays.copyOfRange(values, splitIdx, values.length);

        // Evaluate on test split
        HoltWinters hwEval = new HoltWinters(0.3, 0.1, 0.1, seasonLen);
        double[] testHat = hwEval.forecast(train, test.length);
        AccuracyMetrics metrics = computeMetrics(test, testHat);

        // Refit on full series → production forecast
        HoltWinters hwProd = new HoltWinters(0.3, 0.1, 0.1, seasonLen);
        double[] fc = hwProd.forecast(values, horizon);

        LocalDate start = ts.lastKey().plusDays(1);
        ForecastResult result = new ForecastResult(clusterId, fc, start, metrics);
        results.put(clusterId, result);

        System.out.printf("    Cluster %d: HW fitted | 30d forecast Σ=%.0f | %s%n",
                clusterId, Arrays.stream(fc).sum(), metrics);
        return result;
    }

    /** Fits Holt-Winters for all provided clusters. */
    public void fitAll(Map<Integer, TreeMap<LocalDate, Double>> clusterSeries) {
        System.out.printf("  Fitting [Holt-Winters] for %d clusters …%n",
                clusterSeries.size());
        clusterSeries.forEach((cid, ts) -> fitHoltWinters(cid, ts));
    }

    // ── Accuracy Metrics ──────────────────────────────────────────────────────

    private AccuracyMetrics computeMetrics(double[] actual, double[] predicted) {
        int m = Math.min(actual.length, predicted.length);
        double mae = 0, mse = 0, smape = 0;

        for (int i = 0; i < m; i++) {
            double a = actual[i], p = predicted[i];
            double err = Math.abs(a - p);
            mae += err;
            mse += err * err;
            double denom = (Math.abs(a) + Math.abs(p)) / 2.0;
            smape += denom < 1e-6 ? 0 : (err / denom);
        }
        return new AccuracyMetrics(
                round2(mae / m),
                round2(Math.sqrt(mse / m)),
                round2(smape / m * 100));
    }

    public Map<Integer, AccuracyMetrics> getAllMetrics() {
        Map<Integer, AccuracyMetrics> map = new TreeMap<>();
        results.forEach((cid, r) -> map.put(cid, r.metrics()));
        return map;
    }

    /** Returns 30-day total demand forecast per cluster. */
    public Map<Integer, Double> totalForecastedDemand() {
        Map<Integer, Double> demand = new TreeMap<>();
        results.forEach((cid, r) -> demand.put(cid, Arrays.stream(r.forecastValues()).sum()));
        return demand;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Inner class: Holt-Winters Triple Exponential Smoothing
    // ══════════════════════════════════════════════════════════════════════════
    static class HoltWinters {

        private final double alpha;
        private final double beta;
        private final double gamma;
        private final int seasonLen;

        HoltWinters(double alpha, double beta, double gamma, int seasonLen) {
            this.alpha = alpha;
            this.beta = beta;
            this.gamma = gamma;
            this.seasonLen = seasonLen;
        }

        /**
         * Fits on {@code series} and returns {@code horizon} step-ahead forecasts.
         * Falls back to a flat mean forecast if the series is too short.
         */
        double[] forecast(double[] series, int horizon) {
            int s = seasonLen;
            int n = series.length;

            // Guard: need at least 2 full seasons
            if (n < 2 * s) {
                double mean = Arrays.stream(series).average().orElse(0);
                double[] fc = new double[horizon];
                Arrays.fill(fc, Math.max(0, mean));
                return fc;
            }

            // Initial level, trend, seasonal indices
            double level = initialLevel(series);
            double trend = initialTrend(series);
            double[] seasonal = initialSeasonal(series);

            // Extend seasonal buffer
            List<Double> seasonBuf = new ArrayList<>();
            for (double v : seasonal)
                seasonBuf.add(v);

            // Smoothing loop
            for (int i = 0; i < n; i++) {
                double y = Math.max(series[i], 0);
                double si = seasonBuf.get(seasonBuf.size() - seasonLen);
                double Lprev = level;
                double Tprev = trend;
                level = alpha * (y - si) + (1 - alpha) * (Lprev + Tprev);
                trend = beta * (level - Lprev) + (1 - beta) * Tprev;
                double newSeas = gamma * (y - Lprev - Tprev) + (1 - gamma) * si;
                seasonBuf.add(newSeas);
            }

            // Generate forecast
            double[] fc = new double[horizon];
            for (int h = 1; h <= horizon; h++) {
                int siIdx = seasonBuf.size() - s + (h - 1) % s;
                double si = seasonBuf.get(siIdx);
                fc[h - 1] = Math.max(0, level + h * trend + si);
            }
            return fc;
        }

        private double initialLevel(double[] series) {
            double sum = 0;
            for (int i = 0; i < seasonLen; i++)
                sum += series[i];
            return sum / seasonLen;
        }

        private double initialTrend(double[] series) {
            int s = seasonLen;
            double sum = 0;
            for (int i = 0; i < s; i++) {
                sum += (series[i + s] - series[i]);
            }
            return sum / (s * s);
        }

        private double[] initialSeasonal(double[] series) {
            int s = seasonLen;
            int nSeasons = series.length / s;
            double[] avgs = new double[nSeasons];
            for (int j = 0; j < nSeasons; j++) {
                double sum = 0;
                for (int i = 0; i < s; i++)
                    sum += series[j * s + i];
                avgs[j] = sum / s;
            }
            double[] seasonal = new double[s];
            for (int i = 0; i < s; i++) {
                double sum = 0;
                int cnt = 0;
                for (int j = 0; j < nSeasons; j++) {
                    if (avgs[j] != 0) {
                        sum += series[j * s + i] / avgs[j];
                        cnt++;
                    }
                }
                seasonal[i] = cnt == 0 ? 1.0 : sum / cnt;
            }
            return seasonal;
        }
    }
}
