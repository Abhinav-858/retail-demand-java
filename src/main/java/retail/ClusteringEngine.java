package retail;

import java.util.*;
import java.util.stream.*;

/**
 * MODULE 2 — Clustering Engine
 * -----------------------------
 * Provides:
 *   - Feature standardisation (Z-score)
 *   - Silhouette-based automatic k selection
 *   - K-Means clustering (Lloyd's algorithm)
 *   - Agglomerative Hierarchical clustering (average linkage)
 *   - Silhouette score calculation
 *   - Cluster-member lookup
 */
public class ClusteringEngine {

    // ── Feature column indices (mirrors SkuFeature.toVector()) ───────────────
    private static final String[] FEATURE_NAMES = {
        "unitCost", "leadTime", "txnCount", "totalQty",
        "avgDailyQty", "totalRevenue", "avgRevenue",
        "uniqueCustomers", "activeDays"
    };

    private final List<SkuFeature> features;
    private final double[][]       X;          // scaled feature matrix [n x d]
    private final int              n;           // number of SKUs
    private final int              d;           // number of features

    public int     bestK          = -1;
    public int[]   labelsKmeans   = null;
    public int[]   labelsHier     = null;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ClusteringEngine(List<SkuFeature> features) {
        this.features = features;
        this.n = features.size();
        this.d = FEATURE_NAMES.length;
        this.X = standardise(buildRawMatrix(features));
    }

    // ── Pre-processing ────────────────────────────────────────────────────────

    private double[][] buildRawMatrix(List<SkuFeature> feats) {
        double[][] raw = new double[feats.size()][d];
        for (int i = 0; i < feats.size(); i++) {
            raw[i] = feats.get(i).toVector();
        }
        return raw;
    }

    /** Z-score standardisation per feature column. */
    private double[][] standardise(double[][] raw) {
        double[][] scaled = new double[n][d];
        for (int j = 0; j < d; j++) {
            double mean = 0, std = 0;
            for (double[] row : raw) mean += row[j];
            mean /= n;
            for (double[] row : raw) std += Math.pow(row[j] - mean, 2);
            std = Math.sqrt(std / n + 1e-9);

            for (int i = 0; i < n; i++) {
                scaled[i][j] = (raw[i][j] - mean) / std;
            }
        }
        return scaled;
    }

    // ── Silhouette Sweep ──────────────────────────────────────────────────────

    /**
     * Runs K-Means for each k in [kMin, kMax) and selects the k with
     * the highest silhouette score.
     */
    public int selectK(int kMin, int kMax) {
        Map<Integer, Double> scores = new LinkedHashMap<>();
        for (int k = kMin; k < kMax; k++) {
            int[] labels = kMeansLabels(k, 42);
            double sil   = silhouetteScore(X, labels, k);
            scores.put(k, sil);
            System.out.printf("    k=%d  silhouette=%.4f%n", k, sil);
        }
        bestK = scores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse(kMin);
        System.out.printf("  → Best k=%d  (silhouette=%.4f)%n",
                          bestK, scores.get(bestK));
        return bestK;
    }

    // ── K-Means ───────────────────────────────────────────────────────────────

    public int[] fitKMeans(int k) {
        labelsKmeans = kMeansLabels(k, 42);
        for (int i = 0; i < n; i++) {
            features.get(i).clusterKmeans = labelsKmeans[i];
        }
        System.out.printf("  [K-Means k=%d] sizes=%s%n",
                          k, clusterSizes(labelsKmeans, k));
        return labelsKmeans;
    }

    /** Lloyd's algorithm: run multiple times, keep best inertia. */
    private int[] kMeansLabels(int k, long seed) {
        int[]    bestLabels  = null;
        double   bestInertia = Double.MAX_VALUE;

        Random rng = new Random(seed);
        for (int run = 0; run < 10; run++) {
            // Random initialisation
            double[][] centroids = initCentroids(k, rng);
            int[]      labels    = new int[n];

            for (int iter = 0; iter < 300; iter++) {
                // Assignment step
                boolean changed = false;
                for (int i = 0; i < n; i++) {
                    int prev  = labels[i];
                    labels[i] = nearestCentroid(X[i], centroids);
                    if (labels[i] != prev) changed = true;
                }
                if (!changed) break;

                // Update step
                double[][] newCentroids = new double[k][d];
                int[]      counts       = new int[k];
                for (int i = 0; i < n; i++) {
                    int c = labels[i];
                    for (int j = 0; j < d; j++) newCentroids[c][j] += X[i][j];
                    counts[c]++;
                }
                for (int c = 0; c < k; c++) {
                    if (counts[c] == 0) continue;
                    for (int j = 0; j < d; j++) {
                        newCentroids[c][j] /= counts[c];
                    }
                }
                centroids = newCentroids;
            }

            double inertia = computeInertia(labels, centroids);
            if (inertia < bestInertia) {
                bestInertia = inertia;
                bestLabels  = labels.clone();
            }
        }
        return bestLabels;
    }

    private double[][] initCentroids(int k, Random rng) {
        int[] chosen   = new int[k];
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < k; i++) {
            int idx;
            do { idx = rng.nextInt(n); } while (!seen.add(idx));
            chosen[i] = idx;
        }
        double[][] c = new double[k][d];
        for (int i = 0; i < k; i++) c[i] = X[chosen[i]].clone();
        return c;
    }

    private int nearestCentroid(double[] point, double[][] centroids) {
        int    best = 0;
        double minD = Double.MAX_VALUE;
        for (int c = 0; c < centroids.length; c++) {
            double dist = euclideanSq(point, centroids[c]);
            if (dist < minD) { minD = dist; best = c; }
        }
        return best;
    }

    private double computeInertia(int[] labels, double[][] centroids) {
        double inertia = 0;
        for (int i = 0; i < n; i++) {
            inertia += euclideanSq(X[i], centroids[labels[i]]);
        }
        return inertia;
    }

    // ── Agglomerative Hierarchical (Average Linkage) ──────────────────────────

    public int[] fitHierarchical(int k) {
        labelsHier = agglomerativeLabels(k);
        for (int i = 0; i < n; i++) {
            features.get(i).clusterHier = labelsHier[i];
        }
        System.out.printf("  [Hierarchical k=%d] sizes=%s%n",
                          k, clusterSizes(labelsHier, k));
        return labelsHier;
    }

    /**
     * Bottom-up agglomerative clustering with average linkage.
     * Maintains a list of current clusters; merges the two with minimum
     * average inter-cluster distance until k clusters remain.
     */
    private int[] agglomerativeLabels(int k) {
        // Each cluster starts as one point
        List<List<Integer>> clusters = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Integer> c = new ArrayList<>();
            c.add(i);
            clusters.add(c);
        }

        // Pre-compute full pairwise distance matrix
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = Math.sqrt(euclideanSq(X[i], X[j]));
                dist[i][j] = d;
                dist[j][i] = d;
            }
        }

        while (clusters.size() > k) {
            double minDist = Double.MAX_VALUE;
            int mergeA = -1, mergeB = -1;

            for (int a = 0; a < clusters.size(); a++) {
                for (int b = a + 1; b < clusters.size(); b++) {
                    double avgDist = averageLinkage(
                        clusters.get(a), clusters.get(b), dist);
                    if (avgDist < minDist) {
                        minDist = avgDist; mergeA = a; mergeB = b;
                    }
                }
            }

            // Merge cluster B into cluster A, remove B
            clusters.get(mergeA).addAll(clusters.get(mergeB));
            clusters.remove(mergeB);
        }

        // Convert cluster list to label array
        int[] labels = new int[n];
        for (int c = 0; c < clusters.size(); c++) {
            for (int idx : clusters.get(c)) labels[idx] = c;
        }
        return labels;
    }

    private double averageLinkage(List<Integer> a, List<Integer> b,
                                   double[][] dist) {
        double sum = 0;
        for (int i : a) for (int j : b) sum += dist[i][j];
        return sum / (a.size() * b.size());
    }

    // ── Silhouette Score ──────────────────────────────────────────────────────

    /**
     * Computes the mean Silhouette coefficient over all samples.
     * s(i) = (b(i) - a(i)) / max(a(i), b(i))
     *   a(i) = mean intra-cluster distance
     *   b(i) = mean distance to nearest other cluster
     */
    public static double silhouetteScore(double[][] X, int[] labels, int k) {
        int n = X.length;
        double totalSil = 0;

        for (int i = 0; i < n; i++) {
            // Compute mean distance to each cluster
            double[] clusterDists = new double[k];
            int[]    clusterCounts = new int[k];

            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                clusterDists[labels[j]] +=
                    Math.sqrt(euclideanSq(X[i], X[j]));
                clusterCounts[labels[j]]++;
            }

            // a(i): mean distance to own cluster
            double a = clusterCounts[labels[i]] == 0 ? 0
                : clusterDists[labels[i]] / clusterCounts[labels[i]];

            // b(i): min mean distance to any other cluster
            double b = Double.MAX_VALUE;
            for (int c = 0; c < k; c++) {
                if (c == labels[i] || clusterCounts[c] == 0) continue;
                double meanDist = clusterDists[c] / clusterCounts[c];
                if (meanDist < b) b = meanDist;
            }

            double s = (b == Double.MAX_VALUE) ? 0
                : (b - a) / Math.max(a, b);
            totalSil += s;
        }
        return totalSil / n;
    }

    public Map<String, Double> silhouetteReport() {
        Map<String, Double> report = new LinkedHashMap<>();
        if (labelsKmeans != null) {
            double s = silhouetteScore(X, labelsKmeans, bestK);
            report.put("kmeans", Math.round(s * 10000.0) / 10000.0);
        }
        if (labelsHier != null) {
            double s = silhouetteScore(X, labelsHier, bestK);
            report.put("hierarchical", Math.round(s * 10000.0) / 10000.0);
        }
        System.out.println("  Silhouette scores: " + report);
        return report;
    }

    // ── Cluster Membership ────────────────────────────────────────────────────

    /** Returns {clusterId → List<skuId>} for K-Means or Hierarchical labels. */
    public Map<Integer, List<String>> getClusterMembers(boolean useKmeans) {
        int[] labels = useKmeans ? labelsKmeans : labelsHier;
        Map<Integer, List<String>> members = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            members.computeIfAbsent(labels[i], x -> new ArrayList<>())
                   .add(features.get(i).skuId);
        }
        return members;
    }

    /** Returns a per-cluster average unit cost map (used by LP optimizer). */
    public Map<Integer, Double> avgUnitCostPerCluster() {
        Map<Integer, List<Double>> costs = new TreeMap<>();
        for (SkuFeature f : features) {
            costs.computeIfAbsent(f.clusterKmeans, x -> new ArrayList<>())
                 .add(f.unitCost);
        }
        Map<Integer, Double> avg = new TreeMap<>();
        costs.forEach((c, list) ->
            avg.put(c, list.stream().mapToDouble(Double::doubleValue)
                         .average().orElse(1.0)));
        return avg;
    }

    public List<SkuFeature> getFeatures() { return features; }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static double euclideanSq(double[] a, double[] b) {
        double sum = 0;
        for (int j = 0; j < a.length; j++) sum += (a[j] - b[j]) * (a[j] - b[j]);
        return sum;
    }

    private Map<Integer, Long> clusterSizes(int[] labels, int k) {
        Map<Integer, Long> sizes = new TreeMap<>();
        for (int l : labels) sizes.merge(l, 1L, Long::sum);
        return sizes;
    }
}
