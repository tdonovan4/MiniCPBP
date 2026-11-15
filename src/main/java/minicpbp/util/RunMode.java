/*
 * mini-cpbp, replacing classic propagation by belief propagation
 */

package minicpbp.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Run mode, {@code -Dminicpbp.mode=measure|perf} (default {@code measure}).
 * <ul>
 * <li>{@code measure}: every timer, statistic, probe and trace is available,
 * as before this switch existed.</li>
 * <li>{@code perf}: everything that does not guide the search is off. The
 * clock is not read on the BP hot path, the per-sweep entropy is not computed
 * where only its "is it zero" test is read, and setting a measurement-only
 * property or enabling a trace is an error, so that a measurement run is never
 * mistaken for a performance run.</li>
 * </ul>
 * The search is identical in both modes: same nodes, failures and solutions.
 * Both flags are constants, so the JIT removes the code of the other mode.
 */
public final class RunMode {

    public static final boolean PERF;
    public static final boolean MEASURE;

    /** properties that only measure, trace or dump; rejected in perf mode */
    static final String[] MEASUREMENT_PROPERTIES = {
            "minicpbp.bp.stats", "minicpbp.bp.dumpGraph", "minicpbp.bp.measureNoResidual",
            "minicpbp.bp.calibLog", "minicpbp.bp.confCalib", "minicpbp.bp.sweepCalib", "minicpbp.bp.featCalib",
            "minicpbp.bp.decisionShadow", "minicpbp.bp.shadowRefSweeps", "minicpbp.bp.reuseShadow", "minicpbp.bp.shadow",
            "minicpbp.calib.propProfile",
            "minicpbp.alldiff.stats", "minicpbp.alldiff.harvest", "minicpbp.alldiff.harvestTag", "minicpbp.alldiff.harvestPerBucket",
            "minicpbp.gcc.harvest", "minicpbp.gcc.harvestTag", "minicpbp.gcc.harvestPerBucket", "minicpbp.gcc.foldStats",
            "minicpbp.binpacking.harvest", "minicpbp.binpacking.harvestTag", "minicpbp.binpacking.harvestPerBucket",
            "minicpbp.probe.deltaSparsity", "minicpbp.probe.deltaFile", "minicpbp.probe.sweepTrace",
            "minicpbp.debug.tieStats", "minicpbp.debug.tieDecisions", "minicpbp.debug.lateWeights",
            "minicpbp.value.diag", "minicpbp.lds.trace", "minicpbp.gate.dump", "minicpbp.tree.debug",
            "minicpbp.fzn.stats",
    };

    static {
        String m = System.getProperty("minicpbp.mode", "measure");
        if (!m.equals("measure") && !m.equals("perf"))
            throw new IllegalArgumentException("-Dminicpbp.mode must be measure or perf, got '" + m + "'");
        PERF = m.equals("perf");
        MEASURE = !PERF;
        if (PERF) {
            List<String> set = new ArrayList<>();
            for (String p : MEASUREMENT_PROPERTIES)
                if (System.getProperty(p) != null) set.add(p);
            if (!set.isEmpty())
                throw new IllegalStateException("-Dminicpbp.mode=perf rejects measurement-only properties: " + set
                        + " (use -Dminicpbp.mode=measure)");
        }
    }

    private RunMode() {}

    /** forces the class initialisation, so a misconfigured perf run fails at startup */
    public static void check() {}

    /** throws in perf mode: {@code what} is a measurement feature */
    public static void requireMeasure(String what) {
        if (PERF)
            throw new IllegalStateException(what + " is a measurement feature, unavailable with -Dminicpbp.mode=perf");
    }
}
