package retail;

/**
 * PIPELINE — Intelligent Retail Demand Clustering & Forecast System
 * ==================================================================
 * Wires together all five modules in sequence:
 *
 * [1] TransactionDB — seed & query SQLite
 * [2] ClusteringEngine — K-Means + Hierarchical + Silhouette
 * [3] ForecastModule — Holt-Winters per cluster
 * [4] InventoryOptimizer — LP allocation
 * [5] ReportingModule — formatted console output
 */
public class Main {

    public static void main(String[] args) {
        ReportingModule.section(
                "Intelligent Retail Demand Clustering & Forecast System");

        System.out.println("Starting the web-based dashboard server...");

        try {
            DashboardServer.main(args);
        } catch (Exception e) {
            System.err.println("Failed to start Dashboard Server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}