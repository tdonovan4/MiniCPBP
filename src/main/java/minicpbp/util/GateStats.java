package minicpbp.util;

/**
 * Counters of the producer/link gate on BP (engine.core.ReachProbe): branching
 * decisions taken by the gated heuristic, how many of them used BP's value,
 * and the probe's summary. One JVM solves one instance, so plain statics.
 */
public final class GateStats {
    public static long decisions;
    public static long bpDecisions;
    public static int branchVars;
    public static int reachedBranchVars;
    public static String probe = "";
    /** end-of-run summary of a gate evaluated during search */
    public static java.util.function.Supplier<String> detail = () -> "";

    private GateStats() {
    }

    public static void reset() {
        decisions = 0;
        bpDecisions = 0;
        branchVars = 0;
        reachedBranchVars = 0;
        probe = "";
        detail = () -> "";
    }
}
