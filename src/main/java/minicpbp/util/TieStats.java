package minicpbp.util;

import minicpbp.engine.core.IntVar;

/**
 * Dom/wdeg tie rate (-Dminicpbp.debug.tieStats=true; RESEARCH_PLAN.md, "Min-entropy comparison", hypothesis 1): over
 * the first minicpbp.debug.tieDecisions variable selections (default 500) of dom/wdeg + BP value, how often the best
 * dom/wdeg score is shared by several unbound variables (exact ties, relative 1e-9) or nearly (within 10 %), and, at
 * the last 50th decision recorded (and the last one, decision minicpbp.debug.tieDecisions), how concentrated the failure counts (dom/wdeg weights) are over the constraints: total
 * failures, share of constraints with a failure, share of failures on the top 10 % of constraints, normalized entropy
 * of the failure distribution (1 = uniform over all constraints). Printed to stderr at exit: "c tieStats decisions=..
 * tieShare=.. meanTied=.. tiedFrac=.. nearShare=.. nearFrac=.. failures=.. failedShare=.. top10Share=.. failEntropy=..".
 */
public final class TieStats {
    public static final boolean ON = Boolean.getBoolean("minicpbp.debug.tieStats");
    private static final int MAX = Integer.getInteger("minicpbp.debug.tieDecisions", 500);
    private static long n, tie, near, tiedSum, nearSum;
    private static double tiedFrac, nearFrac;
    private static long failures;
    private static double failedShare, top10Share, failEntropy;

    static {
        if (ON) Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.printf(java.util.Locale.ROOT,
                "c tieStats decisions=%d tieShare=%.4f meanTied=%.2f tiedFrac=%.4f nearShare=%.4f nearFrac=%.4f failures=%d failedShare=%.4f top10Share=%.4f failEntropy=%.4f%n",
                n, n == 0 ? 0 : (double) tie / n, n == 0 ? 0 : (double) tiedSum / n, n == 0 ? 0 : tiedFrac / n,
                n == 0 ? 0 : (double) near / n, n == 0 ? 0 : nearFrac / n, failures, failedShare, top10Share, failEntropy)));
    }

    public static void record(IntVar[] x) {
        if (n >= MAX) return;
        double best = Double.MAX_VALUE;
        int cand = 0;
        for (IntVar v : x) if (v.size() > 1) { cand++; best = Math.min(best, Perturb.domWdeg(v)); }
        if (cand == 0) return;
        int t = 0, nr = 0;
        for (IntVar v : x) {
            if (v.size() <= 1) continue;
            double s = Perturb.domWdeg(v);
            if (s <= best * (1 + 1e-9)) t++;
            if (s <= best * 1.1) nr++;
        }
        n++;
        if (t > 1) tie++;
        if (nr > 1) near++;
        tiedSum += t; nearSum += nr;
        tiedFrac += (double) t / cand; nearFrac += (double) nr / cand;
        if (n == MAX || n % 50 == 0) weights(x[0].getSolver());
    }

    /** concentration of the failure counts over the constraints, as of this decision */
    private static void weights(minicpbp.engine.core.Solver cp) {
        java.util.ArrayList<Integer> f = new java.util.ArrayList<>();
        long tot = 0;
        for (java.util.Iterator<minicpbp.engine.core.Constraint> it = cp.getConstraints().iterator(); it.hasNext(); ) {
            int c = it.next().getFailureCount();
            f.add(c); tot += c;
        }
        failures = tot;
        int m = f.size();
        if (m == 0 || tot == 0) { failedShare = 0; top10Share = 0; failEntropy = 1; return; }
        f.sort(java.util.Collections.reverseOrder());
        int failed = 0; double h = 0; long top = 0;
        for (int i = 0; i < m; i++) {
            int c = f.get(i);
            if (c > 0) { failed++; double p = (double) c / tot; h -= p * Math.log(p); }
            if (i < Math.max(1, m / 10)) top += c;
        }
        failedShare = (double) failed / m;
        top10Share = (double) top / tot;
        failEntropy = m > 1 ? h / Math.log(m) : 1;
    }
}
