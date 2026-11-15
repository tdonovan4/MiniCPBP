/*
 * mini-cpbp: decision-time gate on BP (data_thomas/RESEARCH_PLAN.md,
 * "Decision-time gate -- phase A").
 *
 * At each node, after propagation and before BP, MiniCP asks the gate about
 * the variable x dom/wdeg will branch on. The gate searches the factor graph
 * outward from x, on the current domains, for a producer: a factor f holding a
 * reached variable y with SP_f(y) >= PRODUCER_SP. Found: BP runs and its
 * max-marginal value is used; not found: no BP and the smallest value.
 *
 * Modes (phase A arms):
 *   R1     only the factors holding x, test SP_f(x);
 *   LINKS  best-first from x; from a reached variable v, a factor f passes on
 *          to its other variables if its direct transfer toward v (the side
 *          of x) is >= linkMin, at path strength s(v) * min(1, T_f(v)),
 *          kept if >= ReachProbe.REACH_MIN;
 *   R2ANY  every factor passes, factors within MAX_HOPS hops of x
 *          (-Dminicpbp.gate.hops, default 2: the factors of x and of its
 *          neighbours).
 *
 * Exact measures throughout (ReachProbe.score): phase A asks whether the
 * criterion is right, not whether it is cheap. Above MAX_FACTORS scored
 * factors the gate answers yes (runs BP) and counts a budget hit.
 */

package minicpbp.engine.core;

import minicpbp.util.Belief;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

public final class LocalGate implements Predicate<IntVar> {

    public enum Mode {R1, LINKS, R2ANY}

    static final int MAX_FACTORS = Integer.getInteger("minicpbp.gate.maxFactors", 200);
    static final int MAX_HOPS = Integer.getInteger("minicpbp.gate.hops", 2);

    private final Mode mode;
    private final double linkMin;
    private final Belief rep;
    private final IdentityHashMap<IntVar, List<AbstractConstraint>> incident = new IdentityHashMap<>();

    private IntVar lastX;
    private boolean lastVerdict;

    public long tests, positives, factorsScored, nanos, budgetHits, memoMisses;

    public LocalGate(Solver cp, Mode mode, double linkMin) {
        this.mode = mode;
        this.linkMin = linkMin;
        this.rep = cp.getBeliefRep();
        Iterator<Constraint> it = cp.getConstraints().iterator();
        while (it.hasNext()) {
            Constraint c = it.next();
            if (!(c instanceof AbstractConstraint)) continue;
            IdentityHashMap<IntVar, Boolean> seen = new IdentityHashMap<>();
            for (IntVar v : c.getScope()) {
                IntVar b = v.getBaseVar();
                if (seen.put(b, Boolean.TRUE) != null) continue;
                incident.computeIfAbsent(b, z -> new ArrayList<>()).add((AbstractConstraint) c);
            }
        }
    }

    /** evaluated by MiniCP before BP at every node; the verdict is kept for the branching */
    @Override
    public boolean test(IntVar x) {
        long t0 = minicpbp.util.RunMode.MEASURE ? System.nanoTime() : 0;
        boolean v = decide(x.getBaseVar());
        if (minicpbp.util.RunMode.MEASURE) nanos += System.nanoTime() - t0;
        tests++;
        if (v) positives++;
        lastX = x;
        lastVerdict = v;
        return v;
    }

    /** the verdict for the branching heuristic: the one computed before BP at this node */
    public boolean verdictFor(IntVar x) {
        if (x == lastX) return lastVerdict;
        memoMisses++;
        return test(x);
    }

    private boolean decide(IntVar bx) {
        IdentityHashMap<AbstractConstraint, ReachProbe.Score> scored = new IdentityHashMap<>();
        IdentityHashMap<IntVar, Double> strength = new IdentityHashMap<>();
        IdentityHashMap<IntVar, Integer> hop = new IdentityHashMap<>();
        PriorityQueue<Object[]> pq = new PriorityQueue<>((a, b) -> Double.compare((Double) b[0], (Double) a[0]));
        strength.put(bx, 1.0);
        hop.put(bx, 0);
        pq.add(new Object[]{1.0, bx});
        IdentityHashMap<IntVar, Boolean> done = new IdentityHashMap<>();
        while (!pq.isEmpty()) {
            Object[] top = pq.poll();
            IntVar v = (IntVar) top[1];
            if (done.put(v, Boolean.TRUE) != null) continue;
            double sv = strength.get(v);
            for (AbstractConstraint c : incident.getOrDefault(v, java.util.Collections.emptyList())) {
                if (!c.isActive() || !c.bpParticipant()) continue;
                ReachProbe.Score sc = scored.get(c);
                if (sc == null) {
                    if (scored.size() >= MAX_FACTORS) {
                        budgetHits++;
                        return true;
                    }
                    sc = ReachProbe.score(c, rep, mode == Mode.LINKS);
                    scored.put(c, sc);
                    factorsScored++;
                }
                int tv = indexOf(sc.base, v);
                if (tv < 0) continue; // v bound or absent
                if (sc.sp[tv] >= ReachProbe.PRODUCER_SP) return true;
                if (mode == Mode.R1) continue;
                if (mode == Mode.R2ANY && hop.get(v) >= MAX_HOPS - 1) continue;
                double l = mode == Mode.LINKS ? sc.link[tv] : 1.0;
                if (l < linkMin) continue;
                double cand = sv * Math.min(1.0, l);
                if (cand < ReachProbe.REACH_MIN) continue;
                for (IntVar y : sc.base) {
                    if (y == v || done.containsKey(y)) continue;
                    Double sy = strength.get(y);
                    if (sy == null || cand > sy) {
                        strength.put(y, cand);
                        hop.put(y, hop.get(v) + 1);
                        pq.add(new Object[]{cand, y});
                    }
                }
            }
            if (mode == Mode.R1) break;
        }
        return false;
    }

    private static int indexOf(IntVar[] a, IntVar v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    public String summary() {
        return String.format(java.util.Locale.ROOT,
                "mode=%s,hops=%d,producerSP=%.2f,linkMin=%.2f,tests=%d,positives=%d,factorsScored=%d,gateMs=%s,budgetHits=%d,memoMisses=%d",
                mode, MAX_HOPS, ReachProbe.PRODUCER_SP, linkMin, tests, positives, factorsScored,
                minicpbp.util.RunMode.MEASURE ? Long.toString(nanos / 1000000) : "NA", budgetHits, memoMisses);
    }
}
