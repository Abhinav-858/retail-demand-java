package retail;

import org.apache.commons.math3.optim.*;
import org.apache.commons.math3.optim.linear.*;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;

import java.util.*;

/**
 * MODULE 4 — Inventory Optimisation Unit (Linear Programme)
 * -----------------------------------------------------------
 * Allocates a total monetary budget across demand clusters to maximise
 * demand coverage, subject to:
 *
 *   Objective : Maximise  Σᵢ (demand_i / Σ demand)  ×  xᵢ
 *                         (maximise weighted unit allocation)
 *
 *   Constraint: Σᵢ  cost_i × xᵢ  ≤  totalBudget         (budget)
 *               minStock_i  ≤  xᵢ  ≤  maxStock_i  ∀ i   (capacity)
 *               xᵢ  ≥  0                                 (non-negative)
 *
 * Solved via Apache Commons Math SimplexSolver (revised simplex method).
 */
public class InventoryOptimizer {

    public record AllocationResult(
        Map<Integer, Integer> units,       // cluster → units to stock
        double                totalCost,
        boolean               feasible
    ) {}

    private final Map<Integer, Double> unitCosts;
    private final double               totalBudget;

    public InventoryOptimizer(Map<Integer, Double> unitCosts, double totalBudget) {
        this.unitCosts   = unitCosts;
        this.totalBudget = totalBudget;
    }

    /**
     * Runs the LP and returns the optimal inventory allocation.
     *
     * @param forecastedDemand  {clusterId → 30-day forecasted demand}
     * @param minStock          {clusterId → minimum units required}  (optional)
     * @param maxStock          {clusterId → maximum warehouse capacity} (optional)
     */
    public AllocationResult optimise(
            Map<Integer, Double> forecastedDemand,
            Map<Integer, Double> minStock,
            Map<Integer, Double> maxStock) {

        List<Integer> clusters = new ArrayList<>(
            new TreeSet<>(forecastedDemand.keySet()));
        int n = clusters.size();

        double totalDemand = forecastedDemand.values().stream()
                                .mapToDouble(Double::doubleValue).sum() + 1e-9;

        // ── Objective coefficients (maximise coverage proxy) ──────────────────
        double[] objCoeffs = new double[n];
        for (int i = 0; i < n; i++) {
            objCoeffs[i] = forecastedDemand.get(clusters.get(i)) / totalDemand;
        }
        LinearObjectiveFunction objective =
            new LinearObjectiveFunction(objCoeffs, 0);

        // ── Constraints ───────────────────────────────────────────────────────
        List<LinearConstraint> constraints = new ArrayList<>();

        // Budget: Σ cost_i * x_i <= totalBudget
        double[] costCoeffs = new double[n];
        for (int i = 0; i < n; i++) {
            costCoeffs[i] = unitCosts.getOrDefault(clusters.get(i), 1.0);
        }
        constraints.add(new LinearConstraint(
            costCoeffs, Relationship.LEQ, totalBudget));

        // Per-cluster upper bounds (warehouse capacity)
        for (int i = 0; i < n; i++) {
            int cid = clusters.get(i);
            double demand = forecastedDemand.get(cid);
            double hi = maxStock != null
                ? maxStock.getOrDefault(cid, demand * 2)
                : demand * 2;

            double[] ub = new double[n];
            ub[i] = 1.0;
            constraints.add(new LinearConstraint(ub, Relationship.LEQ, hi));
        }

        // Per-cluster lower bounds as equality-ish: x_i >= minStock_i
        if (minStock != null) {
            for (int i = 0; i < n; i++) {
                int cid = clusters.get(i);
                double lo = minStock.getOrDefault(cid, 0.0);
                if (lo > 0) {
                    double[] lb = new double[n];
                    lb[i] = 1.0;
                    constraints.add(new LinearConstraint(lb, Relationship.GEQ, lo));
                }
            }
        }

        // ── Solve ─────────────────────────────────────────────────────────────
        try {
            SimplexSolver solver = new SimplexSolver();
            PointValuePair solution = solver.optimize(
                new MaxIter(1000),
                objective,
                new LinearConstraintSet(constraints),
                GoalType.MAXIMIZE,
                new NonNegativeConstraint(true)
            );

            double[] x = solution.getPoint();
            Map<Integer, Integer> allocation = new TreeMap<>();
            double totalCost = 0;

            for (int i = 0; i < n; i++) {
                int cid   = clusters.get(i);
                int units = (int) Math.round(x[i]);
                allocation.put(cid, units);
                totalCost += units * unitCosts.getOrDefault(cid, 1.0);
            }

            System.out.printf("  Allocation : %s%n", allocation);
            System.out.printf("  Total cost : $%,.2f  (budget $%,.2f)%n",
                              totalCost, totalBudget);

            return new AllocationResult(allocation, totalCost, true);

        } catch (Exception ex) {
            System.out.printf("  [LP FAILED] %s%n", ex.getMessage());

            // Graceful fallback: proportional greedy allocation
            Map<Integer, Integer> fallback = greedyAllocate(
                clusters, forecastedDemand, costCoeffs);
            return new AllocationResult(fallback, 0, false);
        }
    }

    /** Overload with no explicit stock bounds. */
    public AllocationResult optimise(Map<Integer, Double> forecastedDemand) {
        return optimise(forecastedDemand, null, null);
    }

    // ── Greedy fallback ───────────────────────────────────────────────────────

    private Map<Integer, Integer> greedyAllocate(
            List<Integer> clusters,
            Map<Integer, Double> demand,
            double[] costs) {

        double remaining = totalBudget;
        Map<Integer, Integer> alloc = new TreeMap<>();
        for (int i = 0; i < clusters.size(); i++) {
            int    cid    = clusters.get(i);
            double cost   = costs[i];
            int    units  = (int) Math.min(demand.get(cid),
                                           Math.floor(remaining / cost));
            alloc.put(cid, Math.max(0, units));
            remaining -= alloc.get(cid) * cost;
        }
        return alloc;
    }
}
