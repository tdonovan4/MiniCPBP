/*
 * mini-cp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License  v3
 * as published by the Free Software Foundation.
 *
 * mini-cp is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY.
 * See the GNU Lesser General Public License  for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with mini-cp. If not, see http://www.gnu.org/licenses/lgpl-3.0.en.html
 *
 * Copyright (c)  2018. by Laurent Michel, Pierre Schaus, Pascal Van Hentenryck
 *
 * mini-cpbp, replacing classic propagation by belief propagation
 * Copyright (c)  2019. by Gilles Pesant
 */

package minicpbp.engine.core;

import minicpbp.cp.Factory;
import minicpbp.search.Objective;
import minicpbp.state.StateInt;
import minicpbp.state.StateManager;
import minicpbp.state.StateStack;
import minicpbp.util.exception.InconsistencyException;
import minicpbp.util.Procedure;
import minicpbp.util.Belief;
import minicpbp.util.StdBelief;
import minicpbp.util.LogBelief;
import minicpbp.util.Log;
import minicpbp.engine.constraints.LinEqSystemModP;

import java.util.*;

public class MiniCP implements Solver {

    private Queue<Constraint> propagationQueue = new ArrayDeque<>();
    private List<Procedure> fixPointListeners = new LinkedList<>();
    private List<Procedure> beliefPropaListeners = new LinkedList<>();
    private List<PropagateListener> propagateConstraintListeners = new LinkedList<>();
    private List<DomainOpListener> domainOpListeners = new LinkedList<>();

    private final StateManager sm;

    private StateStack<IntVar> variables;
    private StateStack<Constraint> constraints;

    private Random rand;

    //******** PARAMETERS ********
    // SP  /* support propagation (aka standard constraint propagation) */
    // BP  /* belief propagation */
    // SBP /* first apply support propagation, then belief propagation */
    private static PropaMode mode = PropaMode.SBP;
    // nb of BP iterations performed
    private static int beliefPropaMaxIter = 10;
    // apply damping to variable-to-constraint messages
    private static boolean damping = true;
    // damping factor in interval [0,1] where 1 is equivalent to no damping
    private static double dampingFactor = 0.75;
    // entropy threshold for a variable; below it we should consider that its value is almost certain
    private final double MIN_VAR_ENTROPY = 1.0E-3;
    // entropy tolerance beyond which it is considered different
    private final double ENTROPY_TOLERANCE = 0.01;
    // reset marginals, local beliefs, and previous outside belief before applying BP at each search-tree node
    private static final boolean resetMarginalsBeforeBP = true;
    // take action upon zero/one beliefs: remove/assign the corresponding value
    private static final boolean actOnZeroOneBelief = false;
    /**
     * Relative decrease of the metric to trigger BP; in interval [0,1] where 0
     * triggers whenever any domain changed at all.
     * <p>
     * Exposed as {@code -Dminicpbp.bp.updateThreshold} (F8). Two properties of
     * the metric matter when reading a measurement made under it: it is a
     * global scalar over <em>all</em> registered variables, auxiliaries
     * included, so one variable's collapse can hide every other variable's
     * stability and vice versa; and its denominator {@code sumDomainSizes} is a
     * trailed {@code StateInt}, i.e. the value left by the last ancestor on
     * this path that actually ran BP, not by the parent. Marginals a node
     * branches on are therefore a function of the path, not of the domains.
     */
    private static double beliefUpdateThreshold = minicpbp.util.BPConfig.UPDATE_THRESHOLD;
    /**
     * 2026-08-19 (TODO.md item 3): forces BP at every search node, disabling
     * the "reuse current marginals" shortcut below. With the shortcut on,
     * the marginals a node sees are inherited from whichever ancestor last
     * ran BP, so branching is a function of the PATH, not of the domains.
     */
    private static final boolean ALWAYS_RUN_BP = Boolean.getBoolean("minicpbp.debug.alwaysRunBP");
    private static final boolean SKIP_REUSE_NORMALIZE = Boolean.getBoolean("minicpbp.debug.skipReuseNormalize");
    private static final boolean SCRUB_OUTSIDE_BELIEF = Boolean.getBoolean("minicpbp.debug.scrubOutsideBelief");
    // representation of beliefs: either standard (StdBelief: [0..1]) or log (LogBelief: [-infinity..0])
    private final Belief beliefRep = new StdBelief();
    // SAME   /* constraints all have the same weight; = 1.0 (default) */
    // ARITY  /* a constraint's weight is related to its arity; = 1 + (arity - min_arity)/total_nb_of_vars */
    private static final ConstraintWeighingScheme Wscheme = ConstraintWeighingScheme.SAME;

    //****************************


    // for message damping
    private boolean prevOutsideBeliefRecorded = false;
    private boolean tuneDamping = true;

    // for weighing constraints
    private int minArity;

    // metric to decide whether or not to update beliefs at a search tree node
    private StateInt sumDomainSizes;
    private long trigger = 0;
    private long potentialTrigger = 0;

    // BP scheduling (BP_SCHEDULING.md phase 0.1): the schedule is a policy, not
    // a property of the engine. The default is FloodingScheduler, which is the
    // historical synchronous sweep unchanged.
    private BPGraph bpGraph;
    private BPScheduler scheduler;
    private boolean graphDumped = false;

    /* incremental dirty seeding (BP_WARM_START_EXPERIMENT.md section 1.3):
     * a monotone counter of real invocations, and the trailed epoch of the last
     * one on the current path. */
    private int bpEpoch = 0;
    private StateInt bpPathEpoch;

    /** the array the branching heuristic scans, for a decision-directed stop */
    private IntVar[] branchingOrder;

    public MiniCP(StateManager sm) {
        this.sm = sm;
        variables = new StateStack<>(sm);
        constraints = new StateStack<>(sm);
        // F9: -Dminicpbp.seed makes the RNG reproducible. It does NOT make a run
        // deterministic on its own: the sparse set restores membership but not
        // the ORDER of its elements, and randomValue() indexes into fillArray's
        // output, so -Dminicpbp.debug.sortDomainValues is needed as well. Time
        // that flag before putting it in an arm — it fires per fillArray call.
        rand = (minicpbp.util.BPConfig.SEED == null)
                ? new Random() : new Random(minicpbp.util.BPConfig.SEED);
        sumDomainSizes = sm.makeStateInt(Integer.MAX_VALUE);
        bpPathEpoch = sm.makeStateInt(0);
    }

    public MiniCP(StateManager sm, long seed) {
        this.sm = sm;
        variables = new StateStack<>(sm);
        constraints = new StateStack<>(sm);
        rand = new Random(seed);
        sumDomainSizes = sm.makeStateInt(Integer.MAX_VALUE);
        bpPathEpoch = sm.makeStateInt(0);
    }

    @Override
    public int bpEpoch() {
        return bpEpoch;
    }

    @Override
    public int bpPathEpoch() {
        return bpPathEpoch.value();
    }

    @Override
    public void setBranchingOrder(IntVar[] x) {
        branchingOrder = x;
    }

    /** producer/link gate (ReachProbe): BP runs only where it accepts the decision variable */
    private java.util.function.Predicate<IntVar> bpGate;

    @Override
    public void setBPGate(java.util.function.Predicate<IntVar> gate) {
        bpGate = gate;
    }

    public long trigger() {return trigger;}
    public long potentialTrigger() {return potentialTrigger;}

    /**
     * The BP schedule in force, created on first use from
     * -Dminicpbp.bp.schedule (default: the historical flooding sweep).
     */
    private BPScheduler scheduler() {
        if (scheduler == null) {
            minicpbp.util.BPStats.install();
            switch (minicpbp.util.BPConfig.SCHEDULE) {
                case FLOOD:
                    scheduler = new FloodingScheduler(this);
                    break;
                case SEQ:
                    scheduler = new SequentialScheduler(bpGraph(), false);
                    break;
                case SEQFB:
                    scheduler = new SequentialScheduler(bpGraph(), true);
                    break;
                case TOPO:
                    scheduler = new TopoScheduler(bpGraph(), minicpbp.util.BPConfig.ROOT_SCOPE_COMPONENT);
                    break;
                case RESIDUAL:
                    scheduler = new ResidualScheduler(bpGraph());
                    break;
                case INWARD: // alias: rooted topo, decision variable's component only
                    scheduler = new TopoScheduler(bpGraph(), true);
                    break;
                default:
                    scheduler = new FloodingScheduler(this);
            }
        }
        return scheduler;
    }

    private BPGraph bpGraph() {
        if (bpGraph == null) bpGraph = new BPGraph(this);
        return bpGraph;
    }

    @Override
    public StateManager getStateManager() {
        return sm;
    }

    @Override
    public StateStack<IntVar> getVariables() {
        return variables;
    }

    @Override
    public StateStack<Constraint> getConstraints() {
        return constraints;
    }

    @Override
    public Random getRandomNbGenerator() { return rand; }

    /**
     * Amendment 10b: reseed the solver RNG between dovetail passes, so that
     * one JVM can run the seed ladder a randomized heuristic needs. A
     * branching heuristic that captured {@code rand} at construction keeps the
     * SAME generator object, so it sees the new seed — but a heuristic that
     * cached derived state would not, which is why DovetailSearch rebuilds the
     * branching from a factory on every pass.
     */
    public void reseed(long seed) {
        rand.setSeed(seed);
    }

    /**
     * Amendment 10b: drop the cached BP schedule so the next invocation
     * rebuilds it from the current {@code BPConfig.SCHEDULE}. Only needed when
     * a pass changes the schedule.
     */
    public void resetScheduler() {
        scheduler = null;
    }

    @Override
    public Belief getBeliefRep() {
        return beliefRep;
    }

    // hidden-capture mode (see Solver.beginHiddenCapture): non-null while an
    // encapsulating constraint builds its internal factor graph
    private HiddenGraph capture = null;

    @Override
    public void beginHiddenCapture() {
        if (capture != null)
            throw new IllegalStateException("nested hidden capture");
        capture = new HiddenGraph();
    }

    @Override
    public HiddenGraph endHiddenCapture() {
        if (capture == null)
            throw new IllegalStateException("endHiddenCapture without beginHiddenCapture");
        HiddenGraph g = capture;
        capture = null;
        return g;
    }

    @Override
    public void registerVar(IntVar x) {
        if (capture != null)
            capture.addVariable(x);
        else
            variables.push(x);
    }

    public void setMode(PropaMode mode) {
        MiniCP.mode = mode;
    }

    public PropaMode getMode() {
        return mode;
    }

    public ConstraintWeighingScheme getWeighingScheme() {
        return Wscheme;
    }

    public void setTraceBPFlag(boolean traceBP) {
        Log.setTraceBP(traceBP);
    }

    public void setTraceSearchFlag(boolean traceSearch) {
        Log.setTraceSearch(traceSearch);
    }

    public void setTraceEntropyFlag(boolean traceEntropy) {
        Log.setTraceEntropy(traceEntropy);
    }

    public void setMaxIter(int maxIter) {
        MiniCP.beliefPropaMaxIter = maxIter;
    }

    @Override
    public int getMaxIter() {
        return beliefPropaMaxIter;
    }

    public boolean dampingMessages() {
        return damping;
    }

    public void setDamp(boolean damp) {
        MiniCP.damping = damp;
    }

    public double dampingFactor() {
        return dampingFactor;
    }

    public void setDampingFactor(double dampingFactor) {
        MiniCP.dampingFactor = dampingFactor;
    }

    public boolean prevOutsideBeliefRecorded() {
        return prevOutsideBeliefRecorded;
    }

    public boolean actingOnZeroOneBelief() {
        return actOnZeroOneBelief;
    }

    public boolean tracingSearch() {
        return Log.isTracingSearch();
    }

    public void schedule(Constraint c) {
        if (c.isActive() && !c.isScheduled()) {
            c.setScheduled(true);
            if (capture != null) {
                // during hidden capture, any scheduling comes from posting the
                // internal graph (fresh hidden variables have no outer
                // constraints registered); the owner drains this at post()
                capture.addPending(c);
                return;
            }
            java.util.function.Consumer<Constraint> local = c.localScheduler();
            if (local != null)
                local.accept(c);
            else
                propagationQueue.add(c);
        }
    }

    @Override
    public void onFixPoint(Procedure listener) {
        fixPointListeners.add(listener);
    }

    private void notifyFixPoint() {
        fixPointListeners.forEach(s -> s.call());
    }

    @Override
    public void onPropagateConstraint(PropagateListener listener) {
        propagateConstraintListeners.add(listener);
    }

    @Override
    public void onDomainOp(DomainOpListener listener) {
        domainOpListeners.add(listener);
    }

    @Override
    public void notifyDomainOp(IntVar x, DomainOpKind kind, int v) {
        if (domainOpListeners.isEmpty()) return;
        for (DomainOpListener l : domainOpListeners) {
            l.onOp(x, kind, v);
        }
    }

    @Override
    public void computeMinArity() {
        this.minArity = Integer.MAX_VALUE;
        Iterator<Constraint> iterator = constraints.iterator();
        Constraint c;
        while (iterator.hasNext()) {
            c = iterator.next();
            if (this.minArity > c.arity())
                this.minArity = c.arity();
        }
       iterator = constraints.iterator();
        while (iterator.hasNext()) {
            c = iterator.next();
            double w = 1.0 + ((double) (c.arity() - this.minArity))/ ((double) constraints.size());
            c.setWeight(w);
        }

    }

    @Override
    public int minArity() {
        return this.minArity;
    }

    @Override
    public void fixPoint() {
        // the global queue holds outer constraints only; if a hidden capture
        // is active (a Factory helper posting with enforceFixpoint during an
        // Intension build), suspend it so outer propagation — including any
        // constraint an outer propagator posts dynamically — stays outer
        HiddenGraph stash = capture;
        capture = null;
        try {
            fixPointImpl();
        } finally {
            capture = stash;
        }
    }

    private void fixPointImpl() {
        notifyFixPoint();
        try {
            while(!propagationQueue.isEmpty()) {
                Constraint c = propagationQueue.remove();
                propagations++;
                long tp = PROP_PROFILE ? System.nanoTime() : 0;
                try {
                    propagate(c);
                }
                catch(InconsistencyException e) {
                    // empty the queue and unset the scheduled status
                    c.incrementFailureCount();
                    if (TOP_FRAC > 0 || !LDS_RULE.isEmpty()) { failLevelSum += sm.getLevel(); failCount++; }
                    while (!propagationQueue.isEmpty())
                        propagationQueue.remove().setScheduled(false);
                    throw e;
                }
                finally {
                    if (PROP_PROFILE) {
                        long[] e2 = propProfile.computeIfAbsent(c.getClass(), k -> new long[2]);
                        e2[0]++;
                        e2[1] += System.nanoTime() - tp;
                    }
                }

            }
        }
        catch (NoSuchElementException e) {}
    }

    @Override
    public void onBeliefPropa(Procedure listener) {
        beliefPropaListeners.add(listener);
    }

    private void notifyBeliefPropa() {
        beliefPropaListeners.forEach(s -> s.call());
    }

    @Override
    public int nbBranchingVariables() {
        int count = 0;
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            if(iterator.next().isForBranching())
                count += 1;
        }
        return count;
    }

    /**
     * Belief Propagation
     * standard version, with two distinct message-passing phases
     * first from variables to constraints, and then from constraints to variables
     */
    @Override
    public void beliefPropa() {
        if (minicpbp.util.BPConfig.SHADOW_SPECS.length > 0 && minicpbp.util.BPConfig.ROOT_AT_DECISION) {
            shadowRegions();
            return;
        }
        long t0 = System.nanoTime();
        try {
            beliefPropaImpl();
        } finally {
            minicpbp.util.BPStats.bpNanos += System.nanoTime() - t0;
        }
    }

    private static final double CONF_TRIGGER = Double.parseDouble(System.getProperty("minicpbp.bp.confTrigger", "0"));
    private static final boolean CONF_CALIB = Boolean.getBoolean("minicpbp.bp.confCalib");
    /* depth calibration (-Dminicpbp.bp.sweepCalib, with calibLog): in a rooted
     * K-sweep run the first sweep is inward from the cold state, i.e. exactly
     * the 1-sweep run; x's marginal after sweep 1 is compared with the final one */
    private static final boolean SWEEP_CALIB = Boolean.getBoolean("minicpbp.bp.sweepCalib");
    /* skip-predictor calibration (-Dminicpbp.bp.featCalib, with calibLog; run with
     * -Dminicpbp.debug.skipReuseNormalize=true so that a variable's stale marginal mass on its
     * current domain is the mass left since the last BP): candidate predictors that the stale
     * value of x agrees with fresh BP, written as "feat" rows */
    private static final boolean FEAT_CALIB = Boolean.getBoolean("minicpbp.bp.featCalib");
    /* decision shadow (-Dminicpbp.bp.decisionShadow=entropy|wdeg, with calibLog): at every node, the decision the
     * configured (possibly optimized) BP gives -- variable by min entropy or dom/wdeg, value by max marginal --
     * against the decision of a fresh, cold BP with minicpbp.bp.shadowRefSweeps sweeps (default 3) in a saved
     * state; "dec" rows: setting(reuse|run),varAgree,valueAgreeOnVar,bothAgree,quality,domSize,entropyGap, where
     * valueAgreeOnVar = the fresh argmax on the configured variable is the configured value, quality = fresh
     * marginal of the configured value on the configured variable / fresh max marginal on that variable,
     * entropyGap = fresh entropy of the configured variable - fresh minimum entropy (0 when the variable agrees
     * or under dom/wdeg); the reference does not move BPStats, propagation or trigger counters */
    private static final String DECISION_SHADOW = System.getProperty("minicpbp.bp.decisionShadow", "");
    private static final int SHADOW_REF_SWEEPS = Integer.getInteger("minicpbp.bp.shadowRefSweeps", 3);

    private IntVar shadowDecisionVar() {
        return DECISION_SHADOW.equals("entropy") ? entropyDecisionVar() : wdegDecisionVar();
    }

    /* -Dminicpbp.bp.rootAt=entropy (with rootAtDecision): root the schedule at the variable min entropy picks
     * from the marginals present before BP runs (the stale choice), the closest rooting min entropy allows,
     * since its own decision is only known after BP */
    private static final boolean ROOT_AT_ENTROPY = "entropy".equals(System.getProperty("minicpbp.bp.rootAt"));
    private IntVar entropyRoot;

    /** the unbound branching variable of smallest marginal entropy, ties to branching order (as minEntropy) */
    public IntVar entropyDecisionVar() {
        IntVar best = null;
        double bestE = Double.MAX_VALUE;
        if (branchingOrder != null) {
            for (IntVar v : branchingOrder) {
                if (v.size() <= 1) continue;
                double e = v.entropy();
                if (e < bestE) { bestE = e; best = v; }
            }
        }
        return best;
    }

    private void decisionShadow(boolean reused) {
        if (DECISION_SHADOW.isEmpty() || forceRun || CALIB_LOG.isEmpty()) return;
        IntVar x = shadowDecisionVar();
        if (x == null) return;
        int v = x.valueWithMaxMarginal();
        if (ROOT_AT_ENTROPY && reused) entropyRoot = x; // a run here would root at this stale choice
        boolean savedWarm = minicpbp.util.BPConfig.WARM_START;
        int savedIter = beliefPropaMaxIter;
        // measurement only: the reference BP must not move counters, budgets or the work total
        long c0 = minicpbp.util.BPStats.calls, i0 = minicpbp.util.BPStats.invocations, s0 = minicpbp.util.BPStats.sweeps,
                f0 = minicpbp.util.BPStats.factorUpdates, n0 = minicpbp.util.BPStats.bpNanos, p0 = propagations;
        long t0 = trigger;
        int e0 = bpEpoch;
        IntVar rx = null;
        int rv = 0, rvx = 0;
        double q = 1, eGap = 0;
        sm.saveState();
        try {
            minicpbp.util.BPConfig.WARM_START = false;
            beliefPropaMaxIter = SHADOW_REF_SWEEPS;
            forceRun = true;
            beliefPropaImpl();
            rx = shadowDecisionVar();
            rv = rx == null ? 0 : rx.valueWithMaxMarginal();
            rvx = x.valueWithMaxMarginal();
            double best = beliefRep.rep2std(x.marginal(rvx));
            q = best > 0 ? beliefRep.rep2std(x.marginal(v)) / best : 1.0;
            eGap = rx == null ? 0 : x.entropy() - rx.entropy();
        } catch (InconsistencyException e) {
            return;
        } finally {
            forceRun = false;
            minicpbp.util.BPConfig.WARM_START = savedWarm;
            beliefPropaMaxIter = savedIter;
            sm.restoreState();
            minicpbp.util.BPStats.calls = c0; minicpbp.util.BPStats.invocations = i0; minicpbp.util.BPStats.sweeps = s0;
            minicpbp.util.BPStats.factorUpdates = f0; minicpbp.util.BPStats.bpNanos = n0; propagations = p0;
            trigger = t0;
            bpEpoch = e0; // not trailed: incremental seeding compares stamps with it
        }
        openCalib();
        boolean varAgree = rx == x;
        calibOut.printf(java.util.Locale.ROOT, "dec,%s,%d,%d,%d,%.4f,%d,%.5f%n", reused ? "reuse" : "run",
                varAgree ? 1 : 0, rvx == v ? 1 : 0, varAgree && rv == v ? 1 : 0, q, x.size(), eGap);
    }
    /* stage 3 reuse rules (RESEARCH_PLAN.md, "Candidate optimizations"; all off by default; they
     * refine the reuse trigger and are ignored under alwaysRunBP):
     *   refutationReuse: reuse when the last BP on the path was rooted at the current branching
     *     variable (it was re-picked after x != v);
     *   topDepth=f: BP at every node whose state level is below f x (number of branching variables);
     *   timeCap=c: skip BP whenever the BP time so far exceeds c x the elapsed time since the first
     *     BP call (overrides everything else). */
    private static final boolean REFUTATION_REUSE = Boolean.getBoolean("minicpbp.bp.refutationReuse");
    private static final double TOP_DEPTH = Double.parseDouble(System.getProperty("minicpbp.bp.topDepth", "0"));
    /* topFrac=f: BP at every node whose state level is below f x the mean level of the failures so far
     * (self-calibrating depth scale; topDepth uses the number of branching variables instead) */
    private static final double TOP_FRAC = Double.parseDouble(System.getProperty("minicpbp.bp.topFrac", "0"));
    private long failLevelSum = 0, failCount = 0;
    /* ldsRule (LDS cost model, RESEARCH_PLAN.md "Search-specific BP spending rules"): reuse stale marginals
     * where a wrong value is cheap within the current pass. b0: no discrepancy left on the path; b1: at most
     * one; cost: estimated cost D^(b+1) below ldsCostNodes, D = mean failure level - current level (>= 1).
     * Only adds reuse; outside LDS (budget unknown) it does nothing. */
    private static final String LDS_RULE = System.getProperty("minicpbp.bp.ldsRule", "");
    private static final double LDS_COST_NODES = Double.parseDouble(System.getProperty("minicpbp.bp.ldsCostNodes", "25"));
    private static final double TIME_CAP = Double.parseDouble(System.getProperty("minicpbp.bp.timeCap", "0"));
    private long capT0 = -1;
    public static long stage3Skips, stage3Forced; // counters for the .stats output
    /** propagator executions so far: a deterministic, machine-independent work measure (combined-search budgets) */
    public static long propagations;
    /* propagation profile (-Dminicpbp.calib.propProfile=true): per constraint class, executions and nanoseconds,
     * to derive per-class weights for a deterministic-time budget (the timing adds overhead: profile runs only) */
    private static final boolean PROP_PROFILE = Boolean.getBoolean("minicpbp.calib.propProfile");
    private static final java.util.Map<Class<?>, long[]> propProfile = new java.util.HashMap<>();
    public static String propProfileSummary() {
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<Class<?>, long[]> e : propProfile.entrySet()) {
            if (b.length() > 0) b.append(';');
            b.append(e.getKey().getSimpleName()).append(':').append(e.getValue()[0]).append(':').append(e.getValue()[1]);
        }
        return b.toString();
    }
    /** the branching variable the last BP on the path was rooted at (trailed) */
    private minicpbp.state.State<IntVar> lastBPRoot;

    /** remaining stale marginal mass of v on its current domain (1 = nothing removed since the last BP) */
    private double staleMass(IntVar v) {
        int[] vals = new int[v.size()];
        int n = v.fillArray(vals);
        double z = 0;
        for (int j = 0; j < n; j++) z += Math.max(0, beliefRep.rep2std(v.marginal(vals[j])));
        return Math.min(1.0, z);
    }

    /**
     * Candidate skip predictors for x, read before BP:
     * {surpG: mean -log(stale mass) over variables, massG: mean removed mass, surpX: -log(stale mass of x),
     *  massN: mean removed mass over x's factor neighbours, strongChg: sum over x's factors of
     *  KL(message to x) * removed mass in the factor's other variables, strongChgNorm: the same divided by
     *  the sum of KL, disagree: KL-weighted share of x's factors whose message argmax differs from the
     *  product's argmax, flip: 1 if removing one factor's message changes the argmax, nFactors,
     *  sameRoot: the last BP was rooted at x}
     */
    private double[] staleFeatures(IntVar x) {
        double surp = 0, mass = 0;
        int nv = 0;
        java.util.IdentityHashMap<IntVar, Double> massOf = new java.util.IdentityHashMap<>();
        Iterator<IntVar> it = variables.iterator();
        while (it.hasNext()) {
            IntVar v = it.next();
            double m = staleMass(v);
            massOf.put(v, m);
            surp += -Math.log(Math.max(m, 1e-300));
            mass += 1 - m;
            nv++;
        }
        int[] vals = new int[x.size()];
        int n = x.fillArray(vals);
        java.util.Arrays.sort(vals, 0, n);
        java.util.List<double[]> msgs = new java.util.ArrayList<>();
        java.util.List<Double> chg = new java.util.ArrayList<>();
        java.util.Set<IntVar> nbrs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Iterator<Constraint> ic = constraints.iterator();
        while (ic.hasNext()) {
            Constraint c = ic.next();
            if (!c.isActive() || !c.bpParticipant() || !(c instanceof AbstractConstraint)) continue;
            IntVar[] sc = c.getScope();
            if (sc == null) continue;
            int pos = -1;
            for (int i = 0; i < sc.length; i++) if (sc[i] == x) { pos = i; break; }
            if (pos < 0) continue;
            double[] m = new double[n];
            double z = 0;
            for (int j = 0; j < n; j++) z += m[j] = Math.max(0, ((AbstractConstraint) c).messageToVar(pos, vals[j]));
            for (int j = 0; j < n; j++) m[j] = z > 0 ? m[j] / z : 1.0 / n;
            msgs.add(m);
            double keep = 1;
            for (int i = 0; i < sc.length; i++) {
                if (i == pos) continue;
                Double mu = massOf.get(sc[i]);
                if (mu == null) mu = staleMass(sc[i]);
                keep *= mu;
                nbrs.add(sc[i]);
            }
            chg.add(1 - keep);
        }
        double massN = 0;
        for (IntVar u : nbrs) { Double mu = massOf.get(u); massN += 1 - (mu == null ? staleMass(u) : mu); }
        massN = nbrs.isEmpty() ? 0 : massN / nbrs.size();
        // product of messages in log space, its argmax, KL of each message, disagreement and single-removal flips
        double[] logp = new double[n];
        double[] kl = new double[msgs.size()];
        int[] am = new int[msgs.size()];
        for (int f = 0; f < msgs.size(); f++) {
            double[] m = msgs.get(f);
            double k = Math.log(n);
            int a = 0;
            for (int j = 0; j < n; j++) {
                logp[j] += Math.log(Math.max(m[j], 1e-300));
                if (m[j] > 0) k += m[j] * Math.log(m[j]);
                if (m[j] > m[a]) a = j;
            }
            kl[f] = n < 2 ? 0 : k; am[f] = a;
        }
        int best = 0;
        for (int j = 1; j < n; j++) if (logp[j] > logp[best]) best = j;
        double klSum = 0, dis = 0, strong = 0;
        boolean flip = false;
        for (int f = 0; f < msgs.size(); f++) {
            klSum += kl[f];
            if (am[f] != best) dis += kl[f];
            strong += kl[f] * chg.get(f);
            double[] m = msgs.get(f);
            int b2 = 0;
            for (int j = 1; j < n; j++)
                if (logp[j] - Math.log(Math.max(m[j], 1e-300)) > logp[b2] - Math.log(Math.max(m[b2], 1e-300))) b2 = j;
            if (b2 != best) flip = true;
        }
        boolean same = lastBPRoot != null && lastBPRoot.value() == x;
        return new double[]{nv == 0 ? 0 : surp / nv, nv == 0 ? 0 : mass / nv, -Math.log(Math.max(massOf.getOrDefault(x, 1.0), 1e-300)),
                massN, strong, klSum > 0 ? strong / klSum : 0, klSum > 0 ? dis / klSum : 0, flip ? 1 : 0, msgs.size(), same ? 1 : 0};
    }

    /** the reuse decision: the global shrink trigger, refined by the stage 3 rules (true = reuse stale marginals) */
    private boolean reuseNow(int sum) {
        boolean reuse = sum >= (1.0 - beliefUpdateThreshold) * sumDomainSizes.value();
        if (REFUTATION_REUSE && !reuse && lastBPRoot != null && lastBPRoot.value() != null
                && lastBPRoot.value() == wdegDecisionVar()) { reuse = true; stage3Skips++; }
        if (TOP_DEPTH > 0 && reuse) {
            int n = branchingOrder != null ? branchingOrder.length : variables.size();
            if (sm.getLevel() < TOP_DEPTH * n) { reuse = false; stage3Forced++; }
        }
        if (TOP_FRAC > 0 && reuse && failCount > 0
                && sm.getLevel() < TOP_FRAC * ((double) failLevelSum / failCount)) { reuse = false; stage3Forced++; }
        if (!LDS_RULE.isEmpty() && !reuse && minicpbp.util.SearchContext.ldsBudgetLeft != Integer.MAX_VALUE) {
            int b = minicpbp.util.SearchContext.ldsBudgetLeft;
            boolean cheap;
            if (LDS_RULE.equals("b0")) cheap = b <= 0;
            else if (LDS_RULE.equals("b1")) cheap = b <= 1;
            else {
                double depthLeft = failCount > 0 ? Math.max(1, (double) failLevelSum / failCount - sm.getLevel()) : Double.MAX_VALUE;
                cheap = Math.pow(depthLeft, b + 1) < LDS_COST_NODES;
            }
            if (cheap) { reuse = true; stage3Skips++; }
        }
        if (TIME_CAP > 0 && !reuse) {
            long now = System.nanoTime();
            if (capT0 < 0) capT0 = now;
            if (now > capT0 && minicpbp.util.BPStats.bpNanos > TIME_CAP * (now - capT0)) { reuse = true; stage3Skips++; }
        }
        return reuse;
    }

    private void featRow(String setting, double[] st, double[] ft, long age, double shrink, boolean agree, double quality) {
        if (CALIB_LOG.isEmpty()) return;
        openCalib();
        StringBuilder b = new StringBuilder();
        for (double v : ft) b.append(String.format(java.util.Locale.ROOT, ",%.6g", v));
        calibOut.printf(java.util.Locale.ROOT, "feat,%s,%.5f,%.5f,%.5f,%.5f,%d,%d,%.5f%s,%d,%.4f%n",
                setting, st[0], st[2], st[3], st[4], (int) st[5], age, shrink, b, agree ? 1 : 0, quality);
    }

    /** x's marginal normalized over its current domain (vals), uniform if all zero */
    private double[] domDist(IntVar x, int[] vals) {
        double[] p = new double[vals.length];
        double z = 0;
        for (int j = 0; j < vals.length; j++) z += p[j] = Math.max(0, beliefRep.rep2std(x.marginal(vals[j])));
        for (int j = 0; j < vals.length; j++) p[j] = z > 0 ? p[j] / z : 1.0 / vals.length;
        return p;
    }

    private static double tv(double[] a, double[] b) {
        double d = 0;
        for (int j = 0; j < a.length; j++) d += Math.abs(a[j] - b[j]);
        return d / 2;
    }

    /**
     * Confidence of the stale marginal of x over its current domain:
     * {c = 1 - H(p)/log|D|, argmax (ties to the smallest value)}; c = 0 when
     * |D| = 1 or the marginal is all zero.
     */
    private double[] staleConfidence(IntVar x) {
        int[] vals = new int[x.size()];
        int n = x.fillArray(vals);
        double z = 0;
        double[] p = new double[n];
        for (int j = 0; j < n; j++) z += p[j] = Math.max(0, beliefRep.rep2std(x.marginal(vals[j])));
        int best = vals[0];
        double bestP = -1, h = 0;
        for (int j = 0; j < n; j++) {
            double q = z > 0 ? p[j] / z : 1.0 / n;
            if (q > 0) h -= q * Math.log(q);
            if (q > bestP || (q == bestP && vals[j] < best)) { bestP = q; best = vals[j]; }
        }
        double c = (n < 2 || !(z > 0)) ? 0 : 1 - h / Math.log(n);
        double second = 0;
        for (int j = 0; j < n; j++) {
            double q = z > 0 ? p[j] / z : 1.0 / n;
            if (vals[j] != best && q > second) second = q;
        }
        double kl = n < 2 ? 0 : Math.log(n) - h;
        return new double[]{c, best, kl, bestP - 1.0 / n, bestP - second, n};
    }

    /* per-decision calibration log (-Dminicpbp.bp.calibLog=file): one row per
     * comparison of a stale marginal with a fresh BP run */
    private static final String CALIB_LOG = System.getProperty("minicpbp.bp.calibLog", "");
    private static java.io.PrintWriter calibOut;
    /** nodes visited (beliefPropa calls outside shadows), and the trailed value at the last real BP run on the path */
    private long nodeCalls = 0;
    private minicpbp.state.StateInt lastBPNode;

    /** relative shrinkage of the summed domain sizes since the last BP on the path (the reuse trigger's quantity) */
    private double globalShrink() {
        long sum = 0;
        Iterator<IntVar> it = variables.iterator();
        while (it.hasNext()) sum += it.next().size();
        int ref = sumDomainSizes.value();
        return ref == Integer.MAX_VALUE || ref == 0 ? -1 : 1.0 - (double) sum / ref;
    }

    private void calibRow(String setting, double[] f, long age, double shrink, boolean agree, double quality) {
        if (CALIB_LOG.isEmpty()) return;
        openCalib();
        calibOut.printf(java.util.Locale.ROOT, "%s,%.5f,%.5f,%.5f,%.5f,%d,%d,%.5f,%d,%.4f%n",
                setting, f[0], f[2], f[3], f[4], (int) f[5], age, shrink, agree ? 1 : 0, quality);
    }

    private static void openCalib() {
        if (calibOut == null) {
            try {
                calibOut = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.FileWriter(CALIB_LOG, true)));
                Runtime.getRuntime().addShutdownHook(new Thread(() -> calibOut.flush()));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** depth calibration row: setting "sweep",
     * c1,kl1,strength1,margin1,domSize, staleMargin, agreeStale1 (argmax after sweep 1 == stale argmax),
     * tvStale1 (stale vs sweep 1), age, globalShrink, sweepsRun, agree13, q13 = pK(v1)/pK(vK), tv13 */
    private void sweepRow(IntVar x, int[] vals, double[] stale, double[] staleP, double[] f1, double[] p1,
                          int sweeps, long age, double shrink) {
        if (CALIB_LOG.isEmpty()) return;
        openCalib();
        double[] fK = staleConfidence(x);
        double[] pK = domDist(x, vals);
        int v1 = (int) f1[1], vK = (int) fK[1];
        double pv1 = 0, pvK = 0;
        for (int j = 0; j < vals.length; j++) { if (vals[j] == v1) pv1 = pK[j]; if (vals[j] == vK) pvK = pK[j]; }
        calibOut.printf(java.util.Locale.ROOT, "sweep,%.5f,%.5f,%.5f,%.5f,%d,%.5f,%d,%.5f,%d,%.5f,%d,%d,%.4f,%.5f%n",
                f1[0], f1[2], f1[3], f1[4], vals.length, stale[4], v1 == (int) stale[1] ? 1 : 0, tv(staleP, p1),
                age, shrink, sweeps, v1 == vK ? 1 : 0, pvK > 0 ? pv1 / pvK : 1.0, tv(p1, pK));
    }

    /** reuse shadow: forces the next beliefPropaImpl call past the reuse test */
    private boolean forceRun = false;
    private static final boolean REUSE_SHADOW = Boolean.getBoolean("minicpbp.bp.reuseShadow");

    /**
     * Reuse shadow (-Dminicpbp.bp.reuseShadow): at a node where the 5 % gate
     * reuses stale marginals, records the value they give the decision
     * variable, then runs a fresh BP inside a saved state and compares. The
     * state is restored afterwards (marginals, local beliefs and the trigger's
     * reference are trailed; BP removes no value), so the search continues
     * exactly as the reuse arm would.
     */
    private void reuseShadow() {
        IntVar x = wdegDecisionVar();
        if (x == null) return;
        int reused = x.valueWithMaxMarginal();
        final double[] feat = staleConfidence(x);
        final long age = lastBPNode == null ? -1 : nodeCalls - lastBPNode.value();
        final double shrink = CALIB_LOG.isEmpty() ? 0 : globalShrink();
        final double[] ft = FEAT_CALIB && x.size() > 1 ? staleFeatures(x) : null;
        sm.withNewState(() -> {
            forceRun = true;
            try {
                beliefPropaImpl();
            } finally {
                forceRun = false;
            }
            int fresh = x.valueWithMaxMarginal();
            double best = beliefRep.rep2std(x.marginal(fresh));
            minicpbp.util.RegionShadow.reuseNodes++;
            if (reused == fresh) minicpbp.util.RegionShadow.reuseAgree++;
            minicpbp.util.RegionShadow.reuseQuality += best > 0 ? beliefRep.rep2std(x.marginal(reused)) / best : 1.0;
            calibRow("reuse", feat, age, shrink, reused == fresh, best > 0 ? beliefRep.rep2std(x.marginal(reused)) / best : 1.0);
            if (ft != null) featRow("reuse", feat, ft, age, shrink, reused == fresh, best > 0 ? beliefRep.rep2std(x.marginal(reused)) / best : 1.0);
            // the fallback a skipped BP would use: the smallest value
            if (x.min() == fresh) minicpbp.util.RegionShadow.minAgree++;
            minicpbp.util.RegionShadow.minQuality += best > 0 ? beliefRep.rep2std(x.marginal(x.min())) / best : 1.0;
        });
    }

    /**
     * Shadow measurement (BPConfig.SHADOW_SPECS): BP under each cheaper variant
     * size, then the full run the search uses. Every run starts with a cold
     * reset, so the runs do not see each other's messages. Requires
     * alwaysRunBP, so that every run actually executes.
     */
    private void shadowRegions() {
        String[] specs = minicpbp.util.BPConfig.SHADOW_SPECS;
        if (minicpbp.util.RegionShadow.agree == null) minicpbp.util.RegionShadow.init(specs.length);
        IntVar x = wdegDecisionVar();
        int savedHops = minicpbp.util.BPConfig.REGION_HOPS;
        double savedFrac = minicpbp.util.BPConfig.REGION_FRAC;
        boolean savedWarm = minicpbp.util.BPConfig.WARM_START;
        int savedSweeps = beliefPropaMaxIter;
        int[] val = new int[specs.length];
        try {
            for (int i = 0; i < specs.length; i++) {
                // spec letters: hK region of K hops, pF region of a fraction F of the factors (BFS
                // from x), sK K sweeps, w warm entry (factors outside the region keep the messages
                // of the last BP on the path: a frozen boundary instead of a uniform one)
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("([hpsw])([0-9.]*)").matcher(specs[i]);
                minicpbp.util.BPConfig.REGION_HOPS = savedHops;
                minicpbp.util.BPConfig.REGION_FRAC = savedFrac;
                minicpbp.util.BPConfig.WARM_START = savedWarm;
                beliefPropaMaxIter = savedSweeps;
                while (m.find()) {
                    if (m.group(1).equals("h")) minicpbp.util.BPConfig.REGION_HOPS = Integer.parseInt(m.group(2));
                    else if (m.group(1).equals("p")) minicpbp.util.BPConfig.REGION_FRAC = Double.parseDouble(m.group(2));
                    else if (m.group(1).equals("w")) minicpbp.util.BPConfig.WARM_START = true;
                    else beliefPropaMaxIter = Integer.parseInt(m.group(2));
                }
                // each variant from the node's entry state (needed for warm variants, whose input
                // is the stored messages); requires the newest-first Trailer restore
                sm.saveState(); // withNewState does not restore when the body throws
                try {
                    long u0 = minicpbp.util.BPStats.factorUpdates, t0 = System.nanoTime();
                    beliefPropaImpl();
                    minicpbp.util.RegionShadow.nanos[i] += System.nanoTime() - t0;
                    minicpbp.util.RegionShadow.updates[i] += minicpbp.util.BPStats.factorUpdates - u0;
                    val[i] = x == null ? 0 : x.valueWithMaxMarginal();
                } finally {
                    sm.restoreState();
                }
            }
        } finally {
            minicpbp.util.BPConfig.REGION_HOPS = savedHops;
            minicpbp.util.BPConfig.REGION_FRAC = savedFrac;
            minicpbp.util.BPConfig.WARM_START = savedWarm;
            beliefPropaMaxIter = savedSweeps;
        }
        long u0 = minicpbp.util.BPStats.factorUpdates, t0 = System.nanoTime();
        try {
            beliefPropaImpl();
        } finally {
            long dt = System.nanoTime() - t0;
            minicpbp.util.BPStats.bpNanos += dt;
            minicpbp.util.RegionShadow.fullNanos += dt;
            minicpbp.util.RegionShadow.fullUpdates += minicpbp.util.BPStats.factorUpdates - u0;
        }
        if (x != null) {
            minicpbp.util.RegionShadow.decisions++;
            int full = x.valueWithMaxMarginal();
            double best = beliefRep.rep2std(x.marginal(full));
            for (int i = 0; i < specs.length; i++) {
                if (val[i] == full) minicpbp.util.RegionShadow.agree[i]++;
                minicpbp.util.RegionShadow.quality[i] += best > 0 ? beliefRep.rep2std(x.marginal(val[i])) / best : 1.0;
            }
        }
    }

    private void beliefPropaImpl() {
 //       System.out.println(variables.size()+" variables");
 //       System.out.println(constraints.size()+" constraints");
        Constraint c;
        // producer/link gate: the decision variable is out of every producer's
        // reach, so the branching heuristic will not read its marginal
        if (bpGate != null) {
            IntVar x = wdegDecisionVar();
            if (x == null || !bpGate.test(x))
                return;
        }
        // confidence trigger (-Dminicpbp.bp.confTrigger=tau): reuse the stale
        // marginals when the decision variable already has a strong preference
        // in them, c = 1 - H(p)/log|D| >= tau over its current domain
        IntVar confX = (CONF_TRIGGER > 0 || CONF_CALIB || SWEEP_CALIB || FEAT_CALIB) ? wdegDecisionVar() : null;
        double[] feat = null;
        double confC = -1;
        int staleVal = Integer.MIN_VALUE;
        double[] st = null;
        // nodes since the last real BP on the path, this node included (nodeCalls
        // is incremented further down for the current node)
        long ageBefore = lastBPNode == null ? -1 : nodeCalls + 1 - lastBPNode.value();
        double shrinkBefore = CALIB_LOG.isEmpty() ? 0 : globalShrink();
        if (confX != null) {
            st = staleConfidence(confX);
            if (FEAT_CALIB && CONF_CALIB && !forceRun && confX.size() > 1) feat = staleFeatures(confX);
            confC = st[0];
            staleVal = (int) st[1];
            if (CONF_TRIGGER > 0 && !forceRun && confC >= CONF_TRIGGER) {
                minicpbp.util.ConfStats.reused++;
                return;
            }
        }
        // First decide whether we trigger BP or simply reuse current marginals
        int sum = 0;
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            sum += iterator.next().size();
        }
        potentialTrigger++;
        if (!forceRun) nodeCalls++;
        if (lastBPNode == null) lastBPNode = sm.makeStateInt(0);
        minicpbp.util.BPStats.calls++;
        // Probe K (BP_PROBE_PROTOCOL.md amendment 7): the decision-keyed
        // trigger replaces the 5 % gate below. Static final, folded when off.
        if (!ALWAYS_RUN_BP && minicpbp.util.BPConfig.DECISION_TRIGGER) {
            if (decisionTriggerSkip())
                return; // marginals already renormalized inside the test
            trigger++;
            minicpbp.util.BPStats.invocations++;
            sumDomainSizes.setValue(sum);
            bpEpoch++;
        }
        else if (!ALWAYS_RUN_BP && !forceRun && reuseNow(sum)) { // trigger BP only if domains sufficiently changed (plus stage 3 rules)
            // 2026-08-19 (TODO.md item 3 diagnosis): renormalizing on the
            // reuse path is not idempotent in floating point (v / sum(v)
            // drifts by ulps when sum is ~1 but not exactly 1). Repeated
            // skip-path renormalizations at positions the trail never pops
            // (e.g. the root of successive LDS passes) make marginals drift
            // across passes; entropy-scale ~1e-11 comparisons then amplify
            // ulp drift into different branching. The debug property
            // disables the renormalization to isolate that mechanism.
            if (!SKIP_REUSE_NORMALIZE) {
                iterator = variables.iterator();
                while (iterator.hasNext()) {
                    iterator.next().normalizeMarginals();
                }
            }
            if (REUSE_SHADOW) reuseShadow();
            decisionShadow(true);
            return; // reuse current marginals
        }
        else {
            trigger++;
            minicpbp.util.BPStats.invocations++;
            sumDomainSizes.setValue(sum);
            bpEpoch++;
        }
        // stale min-entropy choice, read before cold reset; the shadow's forced reference keeps the node's root
        if (ROOT_AT_ENTROPY && !forceRun) entropyRoot = entropyDecisionVar();
        notifyBeliefPropa();
        if (lastBPNode != null) lastBPNode.setValue((int) nodeCalls);
        if (FEAT_CALIB || REFUTATION_REUSE) {
            if (lastBPRoot == null) lastBPRoot = sm.makeStateRef(null);
            lastBPRoot.setValue(wdegDecisionVar());
        }
        try {
            if (tuneDamping) { // decide & tune message damping; default: once at the root node
                BPtuneDamping();
                tuneDamping = false;
            }
            // next.md P1a: on this branch the restored (reversible, and thus
            // correctly backtracked) marginals and local beliefs are thrown
            // away and BP reconverges from uniform. WARM_START keeps them and
            // continues from there instead.
            boolean fullDirty = true;
            if (resetMarginalsBeforeBP && !minicpbp.util.BPConfig.WARM_START) {
                coldReset();
            }
            else if (minicpbp.util.BPConfig.WARM_START) {
                fullDirty = warmEntry();
            }
            BPScheduler sched = scheduler();
            // Probe Q (amendment 13): hint the schedule to root its spanning
            // forest at the variable dom/wdeg branching will select, BEFORE the
            // rebuild inside beginInvocation consumes it. wdeg's inputs do not
            // move during sweeps, so one computation per invocation is exact.
            if (minicpbp.util.BPConfig.ROOT_AT_DECISION)
                bpGraph().setPreferredRoot(ROOT_AT_ENTROPY ? entropyRoot : wdegDecisionVar());
            // the seeding must read the watermark of the PREVIOUS invocation on
            // this path, so the update below happens after beginInvocation
            sched.beginInvocation(fullDirty);
            // trailed write, so also guarded: nothing reads it otherwise
            if (minicpbp.util.BPConfig.INCREMENTAL_DIRTY
                    || minicpbp.util.BPConfig.DECISION_TRIGGER) bpPathEpoch.setValue(bpEpoch);
            if (minicpbp.util.BPConfig.DUMP_GRAPH && !graphDumped && bpGraph != null) {
                graphDumped = true;
                // stderr: harnesses redirect stdout while solving
                System.err.println("c bp graph: " + bpGraph.describe());
            }
            final double[] entropy = {1.0};
            decisionVar = null;
            decisionVal = Integer.MIN_VALUE;
            stableDecisionSweeps = 0;
            convergeSnapshotValid = false;
            final minicpbp.util.BPConfig.StopRule rule = minicpbp.util.BPConfig.STOP_RULE;
            final boolean sweepCalib = SWEEP_CALIB && confX != null && !forceRun && confX.size() > 1;
            final int[] scVals = sweepCalib ? new int[confX.size()] : null;
            if (sweepCalib) confX.fillArray(scVals);
            final double[] scStale = sweepCalib ? domDist(confX, scVals) : null;
            final double[][] sc1 = new double[2][];
            final int[] scSweeps = {0};
            sched.run(beliefPropaMaxIter, iter -> {
                if (sweepCalib) {
                    scSweeps[0] = iter;
                    if (sc1[0] == null) { sc1[0] = staleConfidence(confX); sc1[1] = domDist(confX, scVals); }
                }
                long t0 = System.nanoTime();
                try {
                    return sweepMonitor(iter, entropy, rule);
                } finally {
                    minicpbp.util.BPStats.monitorNanos += System.nanoTime() - t0;
                    minicpbp.util.BPStats.monitorCalls++;
                }
            });
            sched.endInvocation();
            if (sweepCalib && sc1[0] != null) sweepRow(confX, scVals, st, scStale, sc1[0], sc1[1], scSweeps[0], ageBefore, shrinkBefore);
            decisionShadow(false);
            if (CONF_CALIB && confX != null && !forceRun) {
                int fresh = confX.valueWithMaxMarginal();
                double best = beliefRep.rep2std(confX.marginal(fresh));
                double q = best > 0 ? beliefRep.rep2std(confX.marginal(staleVal)) / best : 1.0;
                if (!confX.contains(staleVal)) q = 0;
                minicpbp.util.ConfStats.record(confC, staleVal == fresh, q);
                calibRow("every", st, ageBefore, shrinkBefore, staleVal == fresh, q);
                if (feat != null) featRow("every", st, feat, ageBefore, shrinkBefore, staleVal == fresh, q);
            }
        } catch (InconsistencyException e) {
            // empty the queue and unset the scheduled status
            while (!propagationQueue.isEmpty())
                propagationQueue.remove().setScheduled(false);
            throw e;
        }
    }

    /**
     * The per-sweep engine work, called once after every sweep whatever the
     * schedule: the entropy scans, the trace hooks and the stop rule. Extracted
     * from the lambda in {@link #beliefPropaImpl} so that it can be timed as a
     * whole ({@code BPStats.monitorNanos}) and split into the four scans it
     * performs. Semantics are unchanged, including the order of the two entropy
     * computations and the fact that both are computed whatever the rule in
     * force.
     * <p>
     * The four sub-timers exist because the scans are not equally justified.
     * {@code problemEntropy()} feeds the {@code == 0} correctness stop, which is
     * in force in every mode. {@code smallestVariableEntropy()} is read only by
     * the SHIPPED branch, {@code nbBranchingVariables()} only by a logger that
     * returns immediately unless {@code traceEntropy} is set, and the stop rule
     * itself only decides a sweep {@code k+1} that a 1-sweep cap never runs.
     */
    private boolean sweepMonitor(int iter, double[] entropy,
                                 minicpbp.util.BPConfig.StopRule rule) {
        Log.bpIteration(iter, variables);
        // Probe H (BP_PROBE_PROTOCOL.md amendment 4): per-sweep
        // decision trace; static final gate, dead code when off
        if (minicpbp.util.SweepTrace.ENABLED)
            minicpbp.util.SweepTrace.record(this, iter);
        double previousEntropy = entropy[0];
        long t0 = System.nanoTime();
        double currentEntropy = problemEntropy();
        long t1 = System.nanoTime();
        entropy[0] = currentEntropy;
        // read by the SHIPPED branch below and by the traceBP log line, by
        // nothing else. Under leanMonitor it is computed only for those two.
        double smallEntropy = (!minicpbp.util.BPConfig.LEAN_MONITOR
                || rule == minicpbp.util.BPConfig.StopRule.SHIPPED
                || Log.isTracingBP())
                ? smallestVariableEntropy() : Double.MAX_VALUE;
        long t2 = System.nanoTime();
        if (dampingMessages())
            prevOutsideBeliefRecorded = true;
        Log.bpEntropy(currentEntropy, smallEntropy);
        // the argument is a full scan of the variable stack and is evaluated
        // whether or not the logger's body runs
        if (!minicpbp.util.BPConfig.LEAN_MONITOR || Log.isTracingEntropy())
            Log.modelEntropy(variables, nbBranchingVariables());
        long t3 = System.nanoTime();
        minicpbp.util.BPStats.problemEntropyNanos += t1 - t0;
        minicpbp.util.BPStats.smallEntropyNanos += t2 - t1;
        minicpbp.util.BPStats.logArgNanos += t3 - t2;
        try {
            // Stopping criteria. Exactly one rule is in force and it
            // REPLACES the others (F6): the shipped code tested three of
            // them in sequence, so whichever sat highest in the body won,
            // NO_EARLY_STOP returned before STABLE_DECISION_SWEEPS was ever
            // read, and MIN_VAR_ENTROPY fired within one or two sweeps and
            // made the knob below it inert.
            if (currentEntropy == 0) {
                // either all branching vars are bound or BP says there is no
                // solution: a correctness stop, in force in every mode
                return true;
            }
            switch (rule) {
                case FIXED:
                    return false; // R1: measure at a fixed sweep budget
                case CONVERGE:
                    // R2: the only criterion about movement rather than
                    // confidence. The first sweep has nothing to compare
                    // against, so it never stops there.
                    return marginalMovement() <= minicpbp.util.BPConfig.CONVERGE_TOL;
                case DECISION:
                    return decisionSettled(); // R3, Level 2 research
                default:
                    // R0, production as it ships. CAVEAT: only really makes
                    // sense when branching on min entropy or max marginal.
                    if (smallEntropy <= MIN_VAR_ENTROPY) {
                        // at least one variable is nearly certain of its value
                        return true;
                    }
                    // marginals probably did not change either (and won't in the future)
                    return (iter > 1) /* give it a chance to kick in */
                            && (currentEntropy == previousEntropy);
            }
        } finally {
            minicpbp.util.BPStats.stopRuleNanos += System.nanoTime() - t3;
        }
    }

    /**
     * Throws away every stored message and marginal and starts the invocation
     * from uniform. The historical behaviour of a real BP invocation.
     * <p>
     * Note what it costs and what it buys. It discards a consistent trail
     * snapshot of {marginals, localBelief} that backtracking
     * restored correctly, so no work can ever be inherited between nodes and
     * the reuse gate is the only saving available. It also makes the invariant
     * {@code b(v) = prod_c local_c(v)} hold trivially — {@code b} constant at
     * ONE, every factor uniform, hence proportional — which is why the
     * entailment leak the warm path suffers from was never visible here: this
     * wipes the whole product and rebuilds it from the active factors only.
     */
    private void coldReset() {
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            iterator.next().resetMarginals();
        }
        Iterator<Constraint> iteratorC = constraints.iterator();
        while (iteratorC.hasNext()) {
            Constraint c = iteratorC.next();
            if (c.isActive() && c.bpParticipant())
                c.resetLocalBelief();
        }
        if (SCRUB_OUTSIDE_BELIEF) {
            // 2026-08-19 (TODO.md item 3 diagnosis): outsideBelief is a
            // plain (non-trailed) array; residue from previously visited
            // nodes survives backtracking. Scrub to isolate it as a
            // path-dependence carrier.
            iteratorC = constraints.iterator();
            while (iteratorC.hasNext()) {
                Constraint c = iteratorC.next();
                if (c.isActive() && c.bpParticipant())
                    c.resetOutsideBelief();
            }
        }
        prevOutsideBeliefRecorded = false;
    }

    /**
     * Establishes the invariant {@code b(v) = prod_c local_c(v)} over the
     * factors that are still <em>active</em>, at the entry of a warm-started
     * invocation (BP_WARM_START_EXPERIMENT.md F1, F2, F3). One pass, of the
     * same order as one flooding sweep's marginal rebuild.
     * <p>
     * Why the whole product rather than a targeted repair. Constraints
     * deactivate when entailed, and the dead factor's last message stays
     * multiplied into every neighbour's stored marginal forever: nothing
     * divides it out, and {@code BPGraph.resync} cannot, because it multiplies
     * only over active factors. A warm start therefore used to carry a spurious
     * product that grows with depth. Dividing the factor out on
     * {@code setActive(false)} would be cheaper and is riskier (division by a
     * zero-valued message), so it is an optimisation to consider only if this
     * pass shows up in the profile.
     * <p>
     * Applied under <em>every</em> schedule, including flooding. Flooding does
     * rebuild the product from the active factors at the end of each sweep, but
     * only at the end: {@code receiveMessages()} runs first and forms its
     * cavities from the contaminated marginal. And if the entry invariant
     * differed by schedule, the flood-versus-topo contrast would no longer be
     * about the schedule.
     *
     * @return true when the pass had to fall back to a cold reset, so no work
     * can be inherited and the dirty set must be seeded in full
     */
    private boolean warmEntry() {
        long t0 = System.nanoTime();
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            iterator.next().resetMarginals();
        }
        Iterator<Constraint> iteratorC = constraints.iterator();
        while (iteratorC.hasNext()) {
            Constraint c = iteratorC.next();
            if (c.isActive() && c.bpParticipant())
                c.contributeMarginals();
        }
        boolean noMass = false;
        iterator = variables.iterator();
        while (iterator.hasNext()) {
            IntVar v = iterator.next();
            v.normalizeMarginals();
            if (beliefRep.isZero(v.maxMarginal())) noMass = true;
        }
        // F3: cold's first sweep is undamped, so warm's must be too. This was
        // set false only on the cold branch and inside BPtuneDamping, so after
        // the first sweep ever it stayed true and the first message of every
        // warm invocation blended in the ANCESTOR's last message — computed
        // before the branching decision and before the propagation that
        // followed it. A damping semantics nobody chose.
        prevOutsideBeliefRecorded = false;
        minicpbp.util.BPStats.warmEntryRebuilds++;
        minicpbp.util.BPStats.warmEntryNanos += System.nanoTime() - t0;
        if (!noMass) return false;
        // F2: propagation since the last invocation can have removed every value
        // that carried mass, leaving a zero vector no message passing recovers
        // from. The previous repair set that marginal to ONE on every in-domain
        // value while leaving every neighbouring localBelief untouched, which
        // breaks the invariant by a non-constant factor: the next in-place
        // update then reads a "cavity" proportional to 1/local_this, an
        // anti-message maximally contradicting what the constraint just said,
        // and writes it into the marginal for every later factor of the sweep to
        // read. A full cold reset is the only repair that keeps the invariant,
        // and it is exact.
        minicpbp.util.BPStats.warmEntryColdFallbacks++;
        coldReset();
        return true;
    }

    /* R2 (F5): one snapshot of the branchable marginals, in standard
     * representation. O(sum |D|) memory and per-sweep cost, the same order as
     * problemEntropy() and smallestVariableEntropy(), both already computed
     * every sweep. */
    private double[] convergeSnapshot = new double[0];
    private int[] convergeValues = new int[0];
    private boolean convergeSnapshotValid = false;

    /**
     * The largest movement, in standard representation, of any marginal of an
     * unbound branchable variable since the previous sweep — and refreshes the
     * snapshot. Returns {@code Double.MAX_VALUE} on the first sweep of an
     * invocation, which has nothing to compare against.
     * <p>
     * Restricting it to branchable variables is BP_SCHEDULING_TODO.md's Level 1.
     * On this corpus that restriction is inert, since {@code XCSP.solve}
     * declares every model variable branchable (P36), so expect it to cost more
     * sweeps than MIN_VAR_ENTROPY rather than fewer. Its value is that the
     * comparison between schedules becomes a comparison at equal convergence
     * instead of at equal overconfidence.
     * <p>
     * The index alignment across sweeps relies on {@code fillArray} returning
     * the same order twice, which holds within one invocation because BP never
     * changes a domain ({@code actOnZeroOneBelief} is false) and the order only
     * changes when the sparse set is modified.
     */
    private double marginalMovement() {
        int k = 0;
        double max = 0.0;
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            IntVar v = iterator.next();
            if (v.isBound() || !v.isForBranching()) continue;
            if (convergeValues.length < v.size()) convergeValues = new int[2 * v.size()];
            int s = v.fillArray(convergeValues);
            if (convergeSnapshot.length < k + s) {
                double[] b = new double[Math.max(2 * (k + s), 64)];
                System.arraycopy(convergeSnapshot, 0, b, 0, k);
                convergeSnapshot = b;
            }
            for (int j = 0; j < s; j++) {
                double m = beliefRep.rep2std(v.marginal(convergeValues[j]));
                double d = Math.abs(m - convergeSnapshot[k]);
                if (d > max) max = d;
                convergeSnapshot[k++] = m;
            }
        }
        if (!convergeSnapshotValid) {
            convergeSnapshotValid = true;
            return Double.MAX_VALUE;
        }
        return max;
    }

    /**
     * No-bells-and-whistles Belief Propagation
     * runs for a specified number of iterations, without message damping
     */
    public void vanillaBP(int nbIterations) {
        notifyBeliefPropa();
        setDamp(false);
        try {
            // F4: this used to have the reset but no warm branch, so under
            // WARM_START it neither reset, nor re-established the invariant, nor
            // repaired a zero-mass marginal — a live trap for any caller.
            if (resetMarginalsBeforeBP && !minicpbp.util.BPConfig.WARM_START) {
                coldReset();
            } else if (minicpbp.util.BPConfig.WARM_START) {
                warmEntry();
            }
            for (int iter = 1; iter <= nbIterations; iter++) {
                BPiteration();
                Log.bpIteration(iter, variables);
            }
        } catch (InconsistencyException e) {
            // empty the queue and unset the scheduled status
            while (!propagationQueue.isEmpty())
                propagationQueue.remove().setScheduled(false);
            throw e;
        }
    }

    /**
     * Propagate following the right mode
     */
    public void propagateSolver(){
        switch (this.getMode()) {
            case SP:
                this.fixPoint();
                break;
            case BP:
                this.beliefPropa();
                break;
            case SBP:
                this.fixPoint();
                this.beliefPropa();
                break;
        }
    }

    /**
     * tunes message damping for Belief Propagation according to observed entropy
     */
    private void BPtuneDamping() {
        long t0 = System.nanoTime();
        long sweeps0 = minicpbp.util.BPStats.sweeps;
        try {
            BPtuneDampingImpl();
        } finally {
            // F12: this runs once per solver instance at the root, up to 4
            // trials of maxIter sweeps, OUTSIDE the monitored loop and before
            // search.solve is entered — but inside the timed region, hence
            // inside FloorBench's setupMs. With the reuse gate on and one
            // invocation per 34k nodes it can be most of all the BP work in a
            // run, and nothing separated it until now.
            minicpbp.util.BPStats.tuneDampingNanos += System.nanoTime() - t0;
            minicpbp.util.BPStats.tuneDampingSweeps += minicpbp.util.BPStats.sweeps - sweeps0;
        }
    }

    private void BPtuneDampingImpl() {
        final double MIN_DAMPING_FACTOR = 0.5;
        final double DAMPING_FACTOR_DELTA = 0.15;
        Constraint c;
        setDamp(true);
        setDampingFactor(1.0);
        boolean dampingFactorDetermined = false;
        double previousEntropy, currentEntropy;
        double previousDeltaEntropy, currentDeltaEntropy;
        int valleyCount;
        while (!dampingFactorDetermined) {
//            System.out.println("trying DAMPING FACTOR = " + dampingFactor());
            // start afresh
           Iterator<IntVar> iterator = variables.iterator();
            while (iterator.hasNext()) {
                iterator.next().resetMarginals();
            }
            Iterator<Constraint> iteratorC = constraints.iterator();
            while (iteratorC.hasNext()) {
                c = iteratorC.next();
                if (c.isActive() && c.bpParticipant())
                    c.resetLocalBelief();
            }
            prevOutsideBeliefRecorded = false;
            currentEntropy = 1.0;
            currentDeltaEntropy = 0;
            valleyCount = 0;
            dampingFactorDetermined = true;
            minicpbp.util.BPStats.tuneDampingTrials++;
            // BP dive
            for (int iter = 1; iter <= beliefPropaMaxIter; iter++) {
                BPiteration();
                previousEntropy = currentEntropy;
                previousDeltaEntropy = currentDeltaEntropy;
                currentEntropy = problemEntropy();
                currentDeltaEntropy = currentEntropy - previousEntropy;
                prevOutsideBeliefRecorded = true;
 //               System.out.println("iteration " + iter + "; problem entropy = " + currentEntropy + "; previous entropy = " + previousEntropy);
                if (currentEntropy == 0) { // either all branching vars are bound or BP says there's no solution
                    break;
                }
                if (previousDeltaEntropy <= 0 && currentDeltaEntropy > ENTROPY_TOLERANCE) {
 //                   System.out.println("valley");
                    valleyCount++;
                    if (valleyCount >= 2) {
 //                       System.out.println("two valleys ==> oscillation");
                        if (dampingFactor() - DAMPING_FACTOR_DELTA >= MIN_DAMPING_FACTOR) {
                            setDampingFactor(dampingFactor() - DAMPING_FACTOR_DELTA); // increase damping
                            dampingFactorDetermined = false;
                        }
                        break;
                    }
                }
            }
        }
        Log.bpDampingFactor(dampingFactor());
        if (dampingFactor() == 1.0) {
            setDamp(false);
        }
    }

    /**
     * a single iteration of Belief Propagation:
     * from variables to constraints, and then from constraints to variables
     * <p>
     * Used by the paths that must stay on the synchronous sweep whatever
     * schedule is configured: {@code vanillaBP} (documented as
     * no-bells-and-whistles BP) and {@code BPtuneDamping}, whose oscillation
     * test is calibrated on flooding and whose outcome should not become a
     * function of the schedule under measurement.
     */
    private void BPiteration() {
        if (floodSweep == null) floodSweep = new FloodingScheduler(this);
        floodSweep.sweep();
        minicpbp.util.BPStats.sweeps++;
    }

    private FloodingScheduler floodSweep;

    /**
     * Computes a global loss function from the constraints.
     * Currently it sums the losses from each constraint.
     */
    public double globalLossFct() {
        Iterator<Constraint> iteratorC = constraints.iterator();
        while (iteratorC.hasNext()) {
            iteratorC.next().setAuxVarsMarginalsWCounting();
        }
        double loss = 0;
        Constraint c;
        iteratorC = constraints.iterator();
        while (iteratorC.hasNext()) {
            c = iteratorC.next();
            if (c.isActive()) {
                loss += c.loss();
            }
        }
        return loss;
    }

    /**
     * Computes gradients for variable/value pairs from the constraints given outside beliefs.
     */
    public void globalGradients() {
        Iterator<Constraint> iteratorC = constraints.iterator();
        while (iteratorC.hasNext()) {
            iteratorC.next().setAuxVarsMarginalsWCounting();
        }
        Constraint c;
        iteratorC = constraints.iterator();
        Log.gradientHeader();
        while (iteratorC.hasNext()) {
            c = iteratorC.next();
            if (c.isActive()) {
                Log.gradientConstraint(c.getName());
                c.gradients();
            }
        }
    }

    /**
     * computes and returns the current problem entropy (avg normalized entropy of the variables)
     * note: only considers unbound branching variables
     */
    private double problemEntropy() {
        double sumNormalizedEntropy = 0.0;
        int nbUnboundBranchingVar = 0;
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            IntVar v = iterator.next();
            if(!v.isBound() && v.isForBranching()){
                sumNormalizedEntropy += v.entropy()/Math.log(v.size());
                nbUnboundBranchingVar ++;
            }
        }
        return (nbUnboundBranchingVar == 0 ? 0.0 : sumNormalizedEntropy / nbUnboundBranchingVar);
    }

    /**
     * Probe K: the decision-keyed cross-node trigger
     * (BP_PROBE_PROTOCOL.md amendment 7). Returns true when this BP call may
     * SKIP: no ACTIVE constraint's scope contains both the tentative decision
     * variable and a variable touched since the last invocation on this path.
     * <p>
     * The tentative decision variable is the argmin-entropy unbound branching
     * variable computed from the inherited marginals after renormalizing them
     * over the current domains — the same repair the shipped skip path
     * performs, and the same scan as {@link #decisionSettled()} (F7 ordering).
     * One-hop locality is a heuristic: changes that cannot reach the decision
     * variable through any single factor are assumed unable to flip it. The
     * first invocation on a path (pathEpoch 0) always runs.
     */
    private boolean decisionTriggerSkip() {
        int pathEpoch = bpPathEpoch.value();
        if (pathEpoch <= 0) return false; // nothing computed yet on this path
        // the shipped skip path's repair, needed before reading entropies
        Iterator<IntVar> it = variables.iterator();
        while (it.hasNext()) it.next().normalizeMarginals();
        IntVar best = null;
        double bestEntropy = Double.MAX_VALUE;
        if (branchingOrder != null) {
            for (IntVar v : branchingOrder) {
                if (v.size() <= 1) continue;
                double h = v.entropy();
                if (h < bestEntropy) {
                    bestEntropy = h;
                    best = v;
                }
            }
        } else {
            Iterator<IntVar> it2 = variables.iterator();
            while (it2.hasNext()) {
                IntVar v = it2.next();
                if (v.isBound() || !v.isForBranching()) continue;
                double h = v.entropy();
                if (h < bestEntropy) {
                    bestEntropy = h;
                    best = v;
                }
            }
        }
        if (best == null) return true; // nothing left to decide
        IntVar dec = best.getBaseVar();
        Iterator<Constraint> ic = constraints.iterator();
        while (ic.hasNext()) {
            Constraint c = ic.next();
            if (!c.isActive() || !c.bpParticipant()) continue;
            IntVar[] scope = c.getScope();
            boolean hasDec = false, hasTouched = false;
            for (int i = 0; i < scope.length; i++) {
                IntVar b = scope[i].getBaseVar();
                if (b == dec) hasDec = true;
                if (b.bpTouchStamp() >= pathEpoch) hasTouched = true;
                if (hasDec && hasTouched) return false; // coupled: run BP
            }
        }
        return true; // no active factor couples a change to the decision
    }

    /* decision-directed stopping (BP_SCHEDULING.md 1.4) */
    private IntVar decisionVar;
    private int decisionVal;
    private int stableDecisionSweeps;

    /**
     * True once the branching decision a min-entropy heuristic would take has
     * been the same for {@code BPConfig.STABLE_DECISION_SWEEPS} consecutive
     * sweeps.
     * <p>
     * The shipped rule stops the loop when some variable's entropy falls below
     * MIN_VAR_ENTROPY, which is a statement about how confident BP has become,
     * not about whether the decision has settled: measurement showed it stops
     * whichever schedule is most overconfident, while the marginals are still
     * far from a fixed point. What the solver needs is the decision, so this
     * watches the decision.
     * <p>
     * F7: it has to watch the decision search will actually take. The engine's
     * own {@code variables} stack is in registration order, while
     * {@code BranchingScheme.minEntropy} scans the id-sorted array built in
     * {@code XCSP.solve}; both break ties with a strict {@code <}, i.e. by
     * array order, so on tied entropies the two select different variables.
     * The array the heuristic scans is used when it has been registered.
     * <p>
     * Off-by-one, deliberately left as it is and documented instead: the
     * counter increments only on a <em>repeat</em> and the first sweep compares
     * against {@code null}, so {@code stableDecisionSweeps = k} means the same
     * decision was observed on {@code k+1} consecutive sweeps.
     * <p>
     * Residual caveat that cannot be fixed here: {@code valueWithMaxMarginal}
     * breaks its own ties by {@code fillArray} order, which the sparse set does
     * not restore on backtrack. Two calls within one invocation agree, which is
     * all this needs, but the value is not a function of the marginals alone.
     */
    /**
     * The variable dom/wdeg branching will select: strict min of
     * {@code size()/wDeg()} over the heuristic's scan order — the
     * branchingOrder array when registered, else the registration-order stack
     * restricted to branching variables. Replicates
     * {@code BranchingScheme.domWdeg}'s {@code selectMin} exactly. Shared by
     * the decisionRule=wdeg stop (probe O, amendment 11) and the
     * rootAtDecision schedule hint (probe Q, amendment 13); its inputs do not
     * move during BP sweeps, so within one invocation it is a constant.
     */
    public IntVar wdegDecisionVar() {
        IntVar best = null;
        double bestScore = Double.MAX_VALUE;
        if (branchingOrder != null) {
            for (IntVar v : branchingOrder) {
                if (v.size() <= 1) continue; // selectMin's predicate: unbound
                double s = minicpbp.util.Perturb.domWdeg(v);
                if (s < bestScore) {
                    bestScore = s;
                    best = v;
                }
            }
        } else {
            Iterator<IntVar> iterator = variables.iterator();
            while (iterator.hasNext()) {
                IntVar v = iterator.next();
                if (v.isBound() || !v.isForBranching()) continue;
                double s = minicpbp.util.Perturb.domWdeg(v);
                if (s < bestScore) {
                    bestScore = s;
                    best = v;
                }
            }
        }
        return best;
    }

    private boolean decisionSettled() {
        // Probe O (amendment 11): under decisionRule=wdeg the tracked variable
        // replicates BranchingScheme.domWdeg instead of minEntropy. The check
        // then degenerates to argmax stability of that one variable's marginal
        // — which is exactly what dom-wdeg-max-marginal branching needs watched.
        IntVar best;
        if (minicpbp.util.BPConfig.DECISION_RULE == minicpbp.util.BPConfig.DecisionRule.WDEG) {
            best = wdegDecisionVar();
        } else {
            best = null;
            double bestEntropy = Double.MAX_VALUE;
            if (branchingOrder != null) {
                for (IntVar v : branchingOrder) {
                    if (v.size() <= 1) continue; // selectMin's predicate: unbound
                    double h = v.entropy();
                    if (h < bestEntropy) {
                        bestEntropy = h;
                        best = v;
                    }
                }
            } else {
                Iterator<IntVar> iterator = variables.iterator();
                while (iterator.hasNext()) {
                    IntVar v = iterator.next();
                    if (v.isBound() || !v.isForBranching()) continue;
                    double h = v.entropy();
                    if (h < bestEntropy) {
                        bestEntropy = h;
                        best = v;
                    }
                }
            }
        }
        if (best == null) return true; // nothing left to decide
        int val = best.valueWithMaxMarginal();
        if (best == decisionVar && val == decisionVal) stableDecisionSweeps++;
        else stableDecisionSweeps = 0;
        decisionVar = best;
        decisionVal = val;
        return stableDecisionSweeps >= minicpbp.util.BPConfig.STABLE_DECISION_SWEEPS;
    }

    /**
     * computes and returns the smallest variable entropy
     * note: only considers unbound branching variables
     */
    private double smallestVariableEntropy() {
        double minEntropy = Double.MAX_VALUE;
       Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            IntVar v = iterator.next();
            if (!v.isBound() && v.isForBranching()) {
                if (v.entropy() < minEntropy)
                    minEntropy = v.entropy();
            }
        }
        return minEntropy;
    }

    /**
     * computes and returns the smallest marginal
     * note: only considers unbound branching variables
     */
    private double smallestMarginal() {
        double minMarginal = Double.MAX_VALUE;
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            IntVar v = iterator.next();
            if (!v.isBound() && v.isForBranching()) {
                if (v.minMarginal() < minMarginal)
                    minMarginal = v.minMarginal();
            }
        }
        return minMarginal;
    }
    private void propagate(Constraint c) {
        c.setScheduled(false);
        if (c.isActive()) {
            for (PropagateListener l : propagateConstraintListeners) {
                l.onPropagate(c);
            }
            c.propagate();
        }
    }

    @Override
    public Objective minimize(IntVar x) {
        return new Minimize(x);
    }

    @Override
    public Objective maximize(IntVar x) {
        return minimize(Factory.minus(x));
    }

    @Override
    public void post(Constraint c) {
        // no incremental propagation -- wait until all constraints have been posted
        post(c, false);
    }

    @Override
    public void post(Constraint c, boolean enforceFixpoint) {
        if (capture != null)
            capture.addConstraint(c);
        else
            constraints.push(c);
        c.post();
        if (enforceFixpoint) {
            this.fixPoint();
        }
    }

    @Override
    public void post(BoolVar b) {
        post(b,false);
    }

    @Override
    public void post(BoolVar b, boolean enforceFixpoint){
        b.assign(true);
        if (enforceFixpoint) {
            this.fixPoint();
        }
    }

    @Override
    public IntVar[] sample(double fraction, IntVar[] vars) {
 	    final double initialAccuracy = 0.01; // relative error threshold of cell size wrt fraction
 	    final double maxNumerator = 100.0; // beyond this, we relax the accuracy
	    assert (fraction > 0) && (fraction < 1.0);
	    // the prime numbers under 100
	    int primes[] = {5,7,11,13,17,19,23,29,31,37,41,43,47,53,59,61,67,71,73,79,83,89,97}; // don't use very small primes because it leaves very little room for rhs of inequalities
	    int i;
	    // find largest domain element
	    int maxDomElt = 0;
	    for(i=0; i<vars.length; i++) {
	        if (vars[i].max() > maxDomElt)
		        maxDomElt = vars[i].max();
	    }
	    // find the smallest prime at least as large as maxDomElt
	    for(i=0; i<primes.length; i++)
	        if (primes[i] >= maxDomElt) break;
        if (i == primes.length) {
            Log.info("Domain values larger than currently recorded primes!");
            System.exit(0);
        }
        int p = primes[i];
        assert (p <= maxNumerator);
        //  	System.out.println("p = "+p);
        // compute a sufficiently accurate combination of m linear modular constraints
        double accuracy = initialAccuracy;
        int m;
        double exactNumerator, floorNum, ceilNum;
        LinkedList<Integer> factors = new LinkedList<>();
        do {
            m = 0;
            exactNumerator = fraction;
            while (factors.isEmpty()) {
                m++;
                exactNumerator *= p;
                // System.out.println("m="+m+"  exactNum="+exactNumerator+"  epsilon="+accuracy);
                if (Math.abs(exactNumerator - 1.0)/exactNumerator <= accuracy) break; // a system of equality constraints is sufficient
                if (exactNumerator > maxNumerator) {
                    // System.out.println("numerator is getting too big --- relax accuracy");
                    break;
                }
                floorNum = Math.floor(exactNumerator);
                ceilNum = Math.ceil(exactNumerator);
                if (exactNumerator - floorNum < ceilNum - exactNumerator) {
                    // try with floor first
                    factors = accurateFactorization(floorNum, exactNumerator, accuracy, m, p);
                    if (factors.isEmpty())
                        factors = accurateFactorization(ceilNum, exactNumerator, accuracy, m, p);
                }
                else {
                    // try with ceil first
                    factors = accurateFactorization(ceilNum, exactNumerator, accuracy, m, p);
                    if (factors.isEmpty())
                        factors = accurateFactorization(floorNum, exactNumerator, accuracy, m, p);
                }
            }
            accuracy *= 2;
        } while (exactNumerator > maxNumerator);
        // System.out.println("factors: "+factors.toString());
        int nbIneq = factors.size();
        int nbEq = m - nbIneq;
        // System.out.println("nb eq ; ineq "+nbEq+" ; "+nbIneq);
        // set up the linear modular constraints
        Constraint L = null;
        IntVar[] paramVars;
        if (nbEq>0) {
            int[][] Ae = new int[nbEq][vars.length];
            int[] be = new int[nbEq];
            for (i=0; i<nbEq; i++) {
                be[i] = rand.nextInt(p);
                for (int j=0; j<Ae[i].length; j++) {
                    Ae[i][j] = rand.nextInt(p);
                }
            }
            L = Factory.linEqSystemModP(Ae,vars,be,p);
            this.post(L);
            paramVars = ((LinEqSystemModP) L).getParamVars(); // parametric variables of GJE solved form
        }
        else {
            paramVars = vars;
        }
        if (nbIneq>0) {
            IntVar[] augmentedVars = Arrays.copyOf(paramVars, paramVars.length + 1);
            augmentedVars[paramVars.length] = Factory.makeIntVar(this, 1, 1);
            int[][] Ai = new int[nbIneq][augmentedVars.length];
            int[] bi = new int[nbIneq];
            for (i=0; i<nbIneq; i++) {
                bi[i] = factors.remove() - 1;
                // System.out.println("ineq rhs: "+bi[i]);
                for (int j=0; j<Ai[i].length; j++) {
                    Ai[i][j] = rand.nextInt(p);
                }
            }
            L = Factory.linIneqSystemModP(Ai,augmentedVars,bi,p);
            this.post(L);
        }
        return paramVars;
    }	    

    private LinkedList<Integer> accurateFactorization(double intNum, double exactNum, double accuracy, int m, int p) {
	    if (intNum <= 1.0)
	        return new LinkedList<>(); // cannot be factorized
	    double relError = Math.abs(exactNum - intNum) / exactNum;
        //   	System.out.println("exactNum="+exactNum+" intNum="+intNum+" rel error="+relError);
	    if (relError > accuracy)
	        return new LinkedList<>(); // not accurate enough
	    // decompose intNum into the fewest factors (and no more than m), all less than p
	    return factorize((int) intNum, m, p-1);
    }
    
    private LinkedList<Integer> factorize(int n, int m, int f) {
	    LinkedList<Integer> factors = new LinkedList<>();
	    while ((f>1) && (n>1)) {
	        while (n%f==0) {
		        factors.add(f);
		        n /= f;
	        }
	        f--;
	    }
 	    if ((n==1) && (factors.size()<=m))
	        return factors;
	    else
	        return new LinkedList<>(); // n could not be factorized (in at most m factors)
    }
}

