package retail;

/**
 * Holds per-SKU aggregate features used for clustering.
 */
public class SkuFeature {

    public String skuId;
    public String category;
    public double unitCost;
    public int    leadTime;
    public int    txnCount;
    public double totalQty;
    public double avgDailyQty;
    public double totalRevenue;
    public double avgRevenue;
    public int    uniqueCustomers;
    public int    activeDays;

    // Cluster assignments (set by ClusteringEngine)
    public int clusterKmeans = -1;
    public int clusterHier   = -1;

    /** Returns the numeric feature vector used for distance calculations. */
    public double[] toVector() {
        return new double[]{
            unitCost, leadTime, txnCount, totalQty,
            avgDailyQty, totalRevenue, avgRevenue,
            uniqueCustomers, activeDays
        };
    }

    @Override
    public String toString() {
        return String.format("SkuFeature{id=%s, cat=%s, cost=%.2f, " +
                "totalQty=%.0f, revenue=%.2f, kmeans=%d, hier=%d}",
                skuId, category, unitCost, totalQty, totalRevenue,
                clusterKmeans, clusterHier);
    }
}
