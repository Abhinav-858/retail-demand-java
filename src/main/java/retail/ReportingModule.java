package retail;

import java.util.*;
import java.util.stream.*;

/**
 * MODULE 5 — Reporting Module
 * ----------------------------
 * Generates formatted console reports:
 *   1. Cluster Summary   — aggregate stats per cluster
 *   2. Forecast Accuracy — MAE / RMSE / sMAPE per cluster
 *   3. Inventory Allocation — demand vs allocation vs coverage
 */
public class ReportingModule {

    // ── 1. Cluster Summary ────────────────────────────────────────────────────

    /**
     * Prints per-cluster aggregate statistics for the K-Means segmentation.
     */
    public void clusterReport(List<SkuFeature> features) {
        // Group by cluster
        Map<Integer, List<SkuFeature>> groups = new TreeMap<>();
        for (SkuFeature f : features) {
            groups.computeIfAbsent(f.clusterKmeans, k -> new ArrayList<>()).add(f);
        }

        System.out.println("\n── Cluster Summary ──────────────────────────────────────────");
        System.out.printf("%-10s %-6s %-12s %-14s %-14s %-16s %-14s%n",
            "ClusterID", "Size", "AvgUnitCost", "AvgTotalQty",
            "AvgRevenue", "AvgUniqCust", "AvgActiveDays");
        System.out.println("-".repeat(90));

        groups.forEach((cid, list) -> {
            double avgCost  = list.stream().mapToDouble(f -> f.unitCost).average().orElse(0);
            double avgQty   = list.stream().mapToDouble(f -> f.totalQty).average().orElse(0);
            double avgRev   = list.stream().mapToDouble(f -> f.totalRevenue).average().orElse(0);
            double avgCust  = list.stream().mapToDouble(f -> f.uniqueCustomers).average().orElse(0);
            double avgDays  = list.stream().mapToDouble(f -> f.activeDays).average().orElse(0);

            System.out.printf("%-10d %-6d %-12.2f %-14.0f %-14.2f %-16.1f %-14.1f%n",
                cid, list.size(), avgCost, avgQty, avgRev, avgCust, avgDays);
        });
    }

    // ── 2. Forecast Accuracy ──────────────────────────────────────────────────

    public void accuracyReport(Map<Integer, ForecastModule.AccuracyMetrics> metrics) {
        System.out.println("\n── Forecast Accuracy ────────────────────────────────────────");
        System.out.printf("%-12s %-10s %-10s %-10s%n",
                          "ClusterID", "MAE", "RMSE", "sMAPE_%");
        System.out.println("-".repeat(46));

        metrics.forEach((cid, m) ->
            System.out.printf("%-12d %-10.2f %-10.2f %-10.2f%n",
                              cid, m.mae(), m.rmse(), m.smapePct()));
    }

    // ── 3. Inventory Allocation ───────────────────────────────────────────────

    public void allocationReport(
            Map<Integer, Integer>  allocation,
            Map<Integer, Double>   unitCosts,
            Map<Integer, Double>   forecastedDemand) {

        System.out.println("\n── Inventory Allocation ─────────────────────────────────────");
        System.out.printf("%-12s %-18s %-16s %-12s %-14s%n",
            "ClusterID", "ForecastedDemand", "AllocatedUnits",
            "Coverage_%", "TotalCost_$");
        System.out.println("-".repeat(74));

        allocation.forEach((cid, units) -> {
            double demand   = forecastedDemand.getOrDefault(cid, 0.0);
            double cost     = unitCosts.getOrDefault(cid, 0.0);
            double coverage = demand < 1e-6 ? 0 : Math.min(units / demand, 1.0) * 100;
            double total    = units * cost;

            System.out.printf("%-12d %-18.1f %-16d %-12.1f %-14.2f%n",
                cid, demand, units, coverage, total);
        });
    }

    // ── Utility: pretty separator ─────────────────────────────────────────────
    public static void section(String title) {
        String line = "=".repeat(62);
        System.out.println(line);
        System.out.printf("  %s%n", title);
        System.out.println(line);
    }
}
