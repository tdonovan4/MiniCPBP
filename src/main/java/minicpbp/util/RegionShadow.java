package minicpbp.util;

/**
 * Shadow measurement of cheaper BP variants (BPConfig.SHADOW_SPECS): per
 * variant, how often its argmax on the decision variable agrees with full
 * BP's, the quality of its choice under full BP's marginal (marginal of the
 * variant's value / max marginal, summed), and its cost (wall time, factor
 * executions) against full BP.
 */
public final class RegionShadow {
    public static long decisions;
    public static long fullNanos, fullUpdates;
    public static long[] agree, nanos, updates;
    public static double[] quality;

    private RegionShadow() {
    }

    public static void init(int n) {
        agree = new long[n];
        nanos = new long[n];
        updates = new long[n];
        quality = new double[n];
    }

    /** reuse shadow (MiniCP.reuseShadow): nodes where stale marginals were reused */
    public static long reuseNodes, reuseAgree, minAgree;
    public static double reuseQuality, minQuality;

    public static String line() {
        String reuse = reuseNodes == 0 ? "" : String.format(java.util.Locale.ROOT,
                " reuseShadow=nodes:%d,agree:%d,quality:%.2f,minAgree:%d,minQuality:%.2f",
                reuseNodes, reuseAgree, reuseQuality, minAgree, minQuality);
        if (agree == null) return reuse;
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT,
                " shadow=decisions:%d,fullMs:%d,fullUpdates:%d", decisions, fullNanos / 1000000, fullUpdates));
        String[] specs = BPConfig.SHADOW_SPECS;
        for (int i = 0; i < agree.length; i++)
            sb.append(String.format(java.util.Locale.ROOT, ",%s:agree=%d;quality=%.2f;ms=%d;updates=%d",
                    specs[i], agree[i], quality[i], nanos[i] / 1000000, updates[i]));
        return sb.toString() + reuse;
    }
}
