package minicpbp.util;

import minicpbp.engine.core.IntVar;

/**
 * Dom/wdeg tie rate (-Dminicpbp.debug.tieStats=true; RESEARCH_PLAN.md, "Min-entropy comparison", hypothesis 1): over
 * the first minicpbp.debug.tieDecisions variable selections (default 500) of dom/wdeg + BP value, how often the best
 * dom/wdeg score is shared by several unbound variables (exact ties, relative 1e-9) or nearly (within 10 %). Printed
 * to stderr at exit: "c tieStats decisions=.. tieShare=.. meanTied=.. tiedFrac=.. nearShare=.. nearFrac=..".
 */
public final class TieStats {
    public static final boolean ON = Boolean.getBoolean("minicpbp.debug.tieStats");
    private static final int MAX = Integer.getInteger("minicpbp.debug.tieDecisions", 500);
    private static long n, tie, near, tiedSum, nearSum;
    private static double tiedFrac, nearFrac;

    static {
        if (ON) Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.printf(java.util.Locale.ROOT,
                "c tieStats decisions=%d tieShare=%.4f meanTied=%.2f tiedFrac=%.4f nearShare=%.4f nearFrac=%.4f%n",
                n, n == 0 ? 0 : (double) tie / n, n == 0 ? 0 : (double) tiedSum / n, n == 0 ? 0 : tiedFrac / n,
                n == 0 ? 0 : (double) near / n, n == 0 ? 0 : nearFrac / n)));
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
    }
}
