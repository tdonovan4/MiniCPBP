package minicpbp.util;

/**
 * Confidence trigger (MiniCP, -Dminicpbp.bp.confTrigger) and its calibration
 * (-Dminicpbp.bp.confCalib): per bucket of the stale marginal's confidence
 * c = 1 - H/log|D| on the decision variable, how often its argmax agrees with
 * a fresh BP run, and the fresh marginal of the stale choice / fresh max.
 */
public final class ConfStats {
    public static final double[] EDGES = {0.02, 0.05, 0.1, 0.2, 0.4, 0.7};
    public static final long[] n = new long[EDGES.length + 1], agree = new long[EDGES.length + 1];
    public static final double[] quality = new double[EDGES.length + 1];
    public static long reused;

    private ConfStats() {
    }

    public static void record(double c, boolean ag, double q) {
        int b = 0;
        while (b < EDGES.length && c >= EDGES[b]) b++;
        n[b]++;
        if (ag) agree[b]++;
        quality[b] += q;
    }

    public static String line() {
        long tot = 0;
        for (long v : n) tot += v;
        if (tot == 0 && reused == 0) return "";
        StringBuilder sb = new StringBuilder(" conf=reused:" + reused);
        for (int b = 0; b < n.length; b++)
            sb.append(String.format(java.util.Locale.ROOT, ",b%s:%d/%d/%.1f",
                    b == 0 ? "<" + EDGES[0] : ">=" + EDGES[b - 1], n[b], agree[b], quality[b]));
        return sb.toString();
    }
}
