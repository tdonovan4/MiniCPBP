package minicpbp.util;

import minicpbp.engine.core.IntVar;

/**
 * Noise-floor perturbation (data_thomas/CLUSTER_PLAN.md, C2): with -Dminicpbp.perturb.seed=k (k != 0), the dom/wdeg
 * score of each variable is multiplied by a fixed factor 1 + eps*u (u in [-1, 1) from the seed and the variable's
 * name), min entropy's variable choice compares entropies plus eps*u, and BP's max-marginal value choice compares
 * marginals multiplied by 1 + eps*u(variable, value). With
 * eps = 1e-9 (default, -Dminicpbp.perturb.eps) this changes essentially only how exact ties are broken, so the
 * spread over seeds measures the trajectory chaos of one configuration. Deterministic for a given seed.
 */
public final class Perturb {
    private Perturb() { }

    public static final long SEED = Long.getLong("minicpbp.perturb.seed", 0L);
    public static final double EPS = Double.parseDouble(System.getProperty("minicpbp.perturb.eps", "1e-9"));
    public static final boolean ON = SEED != 0;

    private static double u(long a, long b) {
        long h = SEED * 0x9E3779B97F4A7C15L ^ a * 0xC2B2AE3D27D4EB4FL ^ b * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1;
    }

    private static long key(IntVar x) {
        String n = x.getName();
        return n != null ? n.hashCode() : System.identityHashCode(x);
    }

    /** dom/wdeg score size / wDeg, perturbed when ON (shared by the branching schemes and MiniCP.wdegDecisionVar) */
    public static double domWdeg(IntVar x) {
        double s = ((double) x.size()) / ((double) x.wDeg());
        return ON ? s * (1 + EPS * u(key(x), 17)) : s;
    }

    /** marginal entropy, perturbed additively when ON (entropies near 0 are common), for min entropy's variable choice */
    public static double entropy(IntVar x) {
        double e = x.entropy();
        return ON ? e + EPS * u(key(x), 29) : e;
    }

    /** max-marginal value, comparing perturbed marginals; ties to the first in domain order, as valueWithMaxMarginal */
    public static int argmaxMarginal(IntVar x) {
        if (!ON) return x.valueWithMaxMarginal();
        Belief rep = x.getSolver().getBeliefRep();
        int[] vals = new int[x.size()];
        int n = x.fillArray(vals);
        int best = vals[0];
        double bv = -1;
        for (int j = 0; j < n; j++) {
            double m = rep.rep2std(x.marginal(vals[j])) * (1 + EPS * u(key(x), vals[j] + 1000003L));
            if (m > bv) { bv = m; best = vals[j]; }
        }
        return best;
    }
}
