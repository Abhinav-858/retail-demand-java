package retail;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/**
 * MODULE 1 — Transaction DB
 * --------------------------
 * Manages an SQLite database containing:
 * - sku_master : product catalogue (SKU, category, cost, lead time)
 * - customer_master : customer dimension (region, segment)
 * - sales_transactions: daily line-level sales time-series
 *
 * Provides:
 * - Schema creation & synthetic data seeding
 * - Relational-join feature extraction for clustering
 * - Per-cluster time-series aggregation for forecasting
 */
public class TransactionDB implements AutoCloseable {

    private final Connection conn;

    // ── Constructor ───────────────────────────────────────────────────────────

    /** Opens (or creates) a SQLite database at the given path. */
    public TransactionDB(String dbPath) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        conn.setAutoCommit(false);
        createSchema();
    }

    /** Convenience constructor: uses an in-memory database. */
    public TransactionDB() throws SQLException {
        this(":memory:");
    }

    // ── DDL ───────────────────────────────────────────────────────────────────

    private void createSchema() throws SQLException {
        String[] ddl = {
                """
                        CREATE TABLE IF NOT EXISTS sku_master (
                            sku_id      TEXT PRIMARY KEY,
                            category    TEXT,
                            unit_cost   REAL,
                            lead_time   INTEGER
                        )
                        """,
                """
                        CREATE TABLE IF NOT EXISTS customer_master (
                            customer_id TEXT PRIMARY KEY,
                            region      TEXT,
                            segment     TEXT
                        )
                        """,
                """
                        CREATE TABLE IF NOT EXISTS sales_transactions (
                            txn_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                            date        TEXT,
                            sku_id      TEXT REFERENCES sku_master(sku_id),
                            customer_id TEXT REFERENCES customer_master(customer_id),
                            qty_sold    INTEGER,
                            revenue     REAL
                        )
                        """,
                "CREATE INDEX IF NOT EXISTS idx_date ON sales_transactions(date)",
                "CREATE INDEX IF NOT EXISTS idx_sku  ON sales_transactions(sku_id)"
        };

        try (Statement stmt = conn.createStatement()) {
            for (String sql : ddl)
                stmt.execute(sql.trim());
        }
        conn.commit();
    }

    // ── Synthetic Seed ────────────────────────────────────────────────────────

    /**
     * Seeds the database with synthetic SKUs, customers, and transactions.
     *
     * @param nSkus      number of SKU records
     * @param nCustomers number of customer records
     * @param nDays      number of days of history to generate
     * @param seed       random seed for reproducibility
     */
    public void seedSynthetic(int nSkus, int nCustomers,
            int nDays, long seed) throws SQLException {

        Random rng = new Random(seed);
        String[] cats = { "Electronics", "Apparel", "Grocery", "Home" };
        String[] segs = { "Retail", "Wholesale", "Online" };
        String[] regions = { "North", "South", "East", "West" };

        // Build SKU and customer lists
        List<Object[]> skus = new ArrayList<>();
        List<Object[]> customers = new ArrayList<>();

        for (int i = 0; i < nSkus; i++) {
            skus.add(new Object[] {
                    String.format("SKU%03d", i),
                    cats[rng.nextInt(cats.length)],
                    5 + rng.nextDouble() * 195, // unit_cost [5, 200]
                    1 + rng.nextInt(14) // lead_time [1, 14]
            });
        }
        for (int j = 0; j < nCustomers; j++) {
            customers.add(new Object[] {
                    String.format("CUST%03d", j),
                    regions[rng.nextInt(regions.length)],
                    segs[rng.nextInt(segs.length)]
            });
        }

        // Insert SKUs
        String skuSql = "INSERT OR IGNORE INTO sku_master VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(skuSql)) {
            for (Object[] row : skus) {
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setDouble(3, (Double) row[2]);
                ps.setInt(4, (Integer) row[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // Insert customers
        String custSql = "INSERT OR IGNORE INTO customer_master VALUES (?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(custSql)) {
            for (Object[] row : customers) {
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setString(3, (String) row[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // Insert transactions
        String txnSql = "INSERT INTO sales_transactions " +
                "(date,sku_id,customer_id,qty_sold,revenue) VALUES (?,?,?,?,?)";

        LocalDate start = LocalDate.of(2023, 1, 1);
        int totalRows = 0;

        try (PreparedStatement ps = conn.prepareStatement(txnSql)) {
            for (int d = 0; d < nDays; d++) {
                String dateStr = start.plusDays(d).toString();
                int nTxns = 5 + rng.nextInt(15);

                for (int t = 0; t < nTxns; t++) {
                    Object[] sku = skus.get(rng.nextInt(skus.size()));
                    Object[] cust = customers.get(rng.nextInt(customers.size()));
                    int qty = 1 + rng.nextInt(49);
                    double cost = (Double) sku[2];
                    double rev = qty * cost * (1.1 + rng.nextDouble() * 0.7);

                    ps.setString(1, dateStr);
                    ps.setString(2, (String) sku[0]);
                    ps.setString(3, (String) cust[0]);
                    ps.setInt(4, qty);
                    ps.setDouble(5, Math.round(rev * 100.0) / 100.0);
                    ps.addBatch();
                    totalRows++;
                }
            }
            ps.executeBatch();
        }

        conn.commit();
        System.out.printf("[DB] Seeded: %d SKUs | %d customers | %,d transactions | %d days%n",
                nSkus, nCustomers, totalRows, nDays);
    }

    // ── Feature Extraction ────────────────────────────────────────────────────

    /**
     * Returns per-SKU aggregate features via a relational join.
     * Used as input to the clustering engine.
     */
    public List<SkuFeature> skuFeatureMatrix() throws SQLException {
        String sql = """
                SELECT
                    s.sku_id,
                    m.category,
                    m.unit_cost,
                    m.lead_time,
                    COUNT(*)                      AS txn_count,
                    SUM(s.qty_sold)               AS total_qty,
                    AVG(s.qty_sold)               AS avg_daily_qty,
                    SUM(s.revenue)                AS total_revenue,
                    AVG(s.revenue)                AS avg_revenue,
                    COUNT(DISTINCT s.customer_id) AS unique_customers,
                    COUNT(DISTINCT s.date)        AS active_days
                FROM sales_transactions s
                JOIN sku_master m USING (sku_id)
                GROUP BY s.sku_id
                """;

        List<SkuFeature> features = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                SkuFeature f = new SkuFeature();
                f.skuId = rs.getString("sku_id");
                f.category = rs.getString("category");
                f.unitCost = rs.getDouble("unit_cost");
                f.leadTime = rs.getInt("lead_time");
                f.txnCount = rs.getInt("txn_count");
                f.totalQty = rs.getDouble("total_qty");
                f.avgDailyQty = rs.getDouble("avg_daily_qty");
                f.totalRevenue = rs.getDouble("total_revenue");
                f.avgRevenue = rs.getDouble("avg_revenue");
                f.uniqueCustomers = rs.getInt("unique_customers");
                f.activeDays = rs.getInt("active_days");
                features.add(f);
            }
        }
        return features;
    }

    /**
     * Returns an ordered (date → qty) time-series for a cluster
     * (list of SKU IDs), filling missing days with 0.
     */
    public TreeMap<LocalDate, Double> clusterTimeSeries(
            List<String> skuIds) throws SQLException {

        if (skuIds.isEmpty())
            return new TreeMap<>();

        // Build parameterised IN clause
        StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < skuIds.size(); i++) {
            inClause.append(i == 0 ? "?" : ",?");
        }
        String sql = String.format("""
                SELECT date, SUM(qty_sold) AS qty
                FROM sales_transactions
                WHERE sku_id IN (%s)
                GROUP BY date
                ORDER BY date
                """, inClause);

        TreeMap<LocalDate, Double> ts = new TreeMap<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < skuIds.size(); i++) {
                ps.setString(i + 1, skuIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ts.put(LocalDate.parse(rs.getString("date")),
                            rs.getDouble("qty"));
                }
            }
        }

        // Fill gaps with 0 (dense daily series)
        if (!ts.isEmpty()) {
            LocalDate cur = ts.firstKey();
            LocalDate end = ts.lastKey();
            while (!cur.isAfter(end)) {
                ts.putIfAbsent(cur, 0.0);
                cur = cur.plusDays(1);
            }
        }
        return ts;
    }

    /** Checks if the database connection is open and responsive. */
    public boolean isHealthy() {
        try {
            return conn != null && !conn.isClosed() && conn.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    public void close() throws SQLException {
        if (conn != null && !conn.isClosed())
            conn.close();
    }
}
