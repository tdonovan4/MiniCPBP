/*
 * mini-cpbp: back off from BP while it keeps returning flat marginals.
 *
 * A compute gate that uses only past BP outputs, so it is evaluated before BP
 * runs. After a decision whose BP marginal on the branching variable is flat
 * (KL from uniform over its domain below EPS), BP is skipped for the next
 * `wait` decisions and the fallback value is taken (the smallest value, or a
 * uniformly random one with -Dminicpbp.bp.flatBackoffFallback=random; seed with
 * -Dminicpbp.seed); `wait` doubles (up to MAX)
 * while BP stays flat and drops to 0 at the first informative marginal.
 * Motivation: on models where BP mostly has no preference (QRandom, Sat,
 * AllInterval: 68-85 % of decisions below KL 0.1), acting on near-flat
 * marginals is worse than the smallest value, and BP's cost is wasted.
 */

package minicpbp.search;

import minicpbp.engine.core.IntVar;
import minicpbp.util.Belief;

import java.util.function.Predicate;

public final class FlatBackoff implements Predicate<IntVar> {

    private static final boolean RANDOM =
            System.getProperty("minicpbp.bp.flatBackoffFallback", "min").equals("random");
    private final double eps;
    private final int max;
    private int wait = 0, skip = 0;
    private IntVar lastX;
    private boolean lastRan;
    public long decisions, bpDecisions, flatDecisions;

    public FlatBackoff(double eps, int max) {
        this.eps = eps;
        this.max = max;
    }

    /** the BP gate, before BP at every node */
    @Override
    public boolean test(IntVar x) {
        boolean run = skip <= 0;
        lastX = x;
        lastRan = run;
        return run;
    }

    /** the value for the branching heuristic; updates the backoff state */
    public int value(IntVar x) {
        decisions++;
        boolean ran = x == lastX ? lastRan : skip <= 0;
        if (!ran) {
            skip--;
            return fallback(x);
        }
        bpDecisions++;
        Belief rep = x.getSolver().getBeliefRep();
        int[] vals = new int[x.size()];
        int n = x.fillArray(vals);
        double z = 0;
        double[] p = new double[n];
        for (int j = 0; j < n; j++) z += p[j] = Math.max(0, rep.rep2std(x.marginal(vals[j])));
        double kl = 0;
        int best = vals[0];
        double bestP = -1;
        for (int j = 0; j < n; j++) {
            double q = z > 0 ? p[j] / z : 1.0 / n;
            if (q > 0) kl += q * Math.log(q * n);
            if (q > bestP || (q == bestP && vals[j] < best)) { bestP = q; best = vals[j]; }
        }
        if (kl < eps) {
            flatDecisions++;
            wait = wait == 0 ? 1 : Math.min(max, 2 * wait);
            skip = wait;
            return fallback(x);
        }
        wait = 0;
        return best;
    }

    private static int fallback(IntVar x) {
        return RANDOM ? x.randomValue() : x.min();
    }

    public String summary() {
        return "decisions=" + decisions + ",bpDecisions=" + bpDecisions + ",flatDecisions=" + flatDecisions;
    }
}
