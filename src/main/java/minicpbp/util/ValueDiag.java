package minicpbp.util;

/**
 * Diagnostics of BP's value choice under dom/wdeg (-Dminicpbp.value.diag):
 * per decision, how flat the branching variable's marginal is (KL from
 * uniform over its domain), the gap between its two largest marginals, and
 * whether the max-marginal value is the smallest value. With
 * -Dminicpbp.value.flatMin=eps, a marginal with KL < eps falls back to the
 * smallest value, or to a uniformly random one with
 * -Dminicpbp.value.flatFallback=random.
 */
public final class ValueDiag {
    public static final boolean ON = Boolean.getBoolean("minicpbp.value.diag");
    public static final double FLAT_MIN = Double.parseDouble(System.getProperty("minicpbp.value.flatMin", "0"));
    /** value taken below FLAT_MIN: min (default) or random (seed with -Dminicpbp.seed) */
    public static final boolean FALLBACK_RANDOM = System.getProperty("minicpbp.value.flatFallback", "min").equals("random");
    public static long decisions, sameAsMin, flat001, flat01, flat05, gap001, fallbacks;
    public static double klSum;

    private ValueDiag() {
    }

    public static String line() {
        if (decisions == 0) return "";
        return String.format(java.util.Locale.ROOT,
                " valueDiag=decisions:%d,sameAsMin:%d,klMean:%.3f,klBelow0.01:%d,klBelow0.1:%d,klBelow0.5:%d,gapBelow0.01:%d,flatFallbacks:%d",
                decisions, sameAsMin, klSum / decisions, flat001, flat01, flat05, gap001, fallbacks);
    }
}
