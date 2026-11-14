/*
 * mini-cpbp: root probe of producers and links (data_thomas/RESEARCH_PLAN.md
 * WP0, TOPO_USE_CASE_SUMMARY.md sections 2.2-2.4 and 4.2).
 *
 * BP helps a decision on x only if a producer -- a factor whose solutions
 * favour some values -- reaches x through links that pass its information on.
 * Both quantities are measured with each factor's own updateBelief(), so every
 * constraint type is covered without per-type proxies:
 *
 *   - producer strength SP_f(y) = KL(P_f(y) || uniform over D(y)), with P_f(y)
 *     the factor's outgoing message to y under uniform incoming messages,
 *     i.e. its solution density on y for an exact factor;
 *   - link strength toward y, T_f(y): every unbound input t receives a random
 *     message of relative strength RHO, i.e. KL from uniform = RHO·log|D(t)|,
 *     and T_f(y) = KL(m_y || m0_y) / (RHO·log|D(y)|), with m_y the factor's
 *     message to y and m0_y its message under uniform inputs, averaged over
 *     DRAWS draws. Strength is relative to the domain's capacity because a
 *     fixed 0.5 nats is strong on 3 values and almost nothing on 121
 *     (equality still gives T = 1 exactly). This
 *     is the ground truth of the synthetic link validation
 *     (PROXY_VALIDATION_PLAN.md, T_all), computed with the factor's own
 *     updateBelief(), so it captures joint (synergistic) transfer, such as a
 *     sum y = t - sum(others), and the approximation the solver actually
 *     runs (Soules bound for allDifferent above 7 free values). One call
 *     gives every target, since a message to y never reads y's input.
 *
 * Reach: y is reached directly by f if SP_f(y) >= PRODUCER_SP. From a reached
 * variable v, a factor g holding v passes on to its other variables y with
 * T_g(y) >= LINK_MIN, at strength s(v) * min(1, T_g(y)); y is reached if its
 * best path strength is >= REACH_MIN. SP > 0.05 from the synthetic producer
 * validation. RHO = 0.3 matches the synthetic s = 0.5 nats at about 5 values.
 * LINK_MIN = 0.2 separates, in the synthetic T_all at s = 0.5,
 * the weak classes (clauses 0.08, regular 0.13, <= sums 0.16) from the
 * collective relays (cardinality 0.28, element 0.33, allDifferent 0.37).
 *
 * A factor whose probe throws or exceeds the budget counts as a non-weak link
 * everywhere, so a gate built on this runs BP rather than skips it.
 *
 * The probe writes the factors' local and outside beliefs and resets them to
 * uniform afterwards, so it must run before the first BP invocation.
 */

package minicpbp.engine.core;

import minicpbp.util.Belief;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

public final class ReachProbe {

    public static final double PRODUCER_SP = Double.parseDouble(System.getProperty("minicpbp.gate.producerSP", "0.05"));
    public static final double LINK_MIN = Double.parseDouble(System.getProperty("minicpbp.gate.link", "0.2"));
    public static final double REACH_MIN = Double.parseDouble(System.getProperty("minicpbp.gate.reach", "0.1"));
    /** strength of the random input messages, as a share of log|D| */
    static final double RHO = Double.parseDouble(System.getProperty("minicpbp.gate.inputStrength", "0.3"));
    /** random draws per factor */
    static final int DRAWS = Integer.getInteger("minicpbp.gate.draws", 4);
    /** wall-clock budget of the transfer phase */
    static final boolean DUMP = Boolean.getBoolean("minicpbp.gate.dump");
    static final long BUDGET_MS = Long.getLong("minicpbp.gate.budgetMs", 20000L);

    public static final class Result {
        /** base variable -> best path strength from a producer */
        public final IdentityHashMap<IntVar, Double> reach = new IdentityHashMap<>();
        public int factors, producers, failed, unconditioned, links, weakLinks;
        public long transferCalls, ms;

        public boolean reached(IntVar x) {
            return reach.containsKey(x.getBaseVar());
        }

        public int reachedAmong(IntVar[] xs) {
            int n = 0;
            for (IntVar x : xs) if (!x.isBound() && reached(x)) n++;
            return n;
        }

        public String summary() {
            return "factors=" + factors + " producers=" + producers + " links=" + links
                    + " weakLinks=" + weakLinks + " failed=" + failed + " unconditioned=" + unconditioned
                    + " transferCalls=" + transferCalls + " ms=" + ms;
        }
    }

    /** one factor's measurement: per distinct unbound base variable */
    private static final class Fac {
        int[] var;     // compact variable index
        double[] sp;   // SP_f(y)
        double[] link; // L_f(y)
    }

    private ReachProbe() {
    }

    /** one factor at the current search state: per distinct unbound base variable */
    public static final class Score {
        public IntVar[] base;
        public double[] sp;   // SP_f(y)
        public double[] link; // T_f(y); +inf when transfer was not computed
    }

    /**
     * Scores one factor on the current domains, for a gate evaluated during
     * search. The factor's local and outside beliefs are left uniform, which is
     * harmless only because every BP invocation starts with a cold reset
     * (MiniCP.resetMarginalsBeforeBP, warm start off). A factor whose
     * measurement fails scores +inf everywhere, so a gate runs BP.
     */
    public static Score score(AbstractConstraint c, Belief rep, boolean transfer) {
        IntVar[] scope = c.getScope();
        int[] pos = new int[scope.length];
        int k = 0;
        IdentityHashMap<IntVar, Boolean> seen = new IdentityHashMap<>();
        for (int i = 0; i < scope.length; i++) {
            if (scope[i].isBound()) continue;
            if (seen.put(scope[i].getBaseVar(), Boolean.TRUE) != null) continue;
            pos[k++] = i;
        }
        Score sc = new Score();
        sc.base = new IntVar[k];
        for (int t = 0; t < k; t++) sc.base[t] = scope[pos[t]].getBaseVar();
        Fac f = new Fac();
        f.sp = new double[k];
        f.link = new double[k];
        if (k == 0) {
            sc.sp = f.sp;
            sc.link = f.link;
            return sc;
        }
        try {
            measure(c, scope, pos, k, f, rep, new Result(), !transfer);
        } catch (RuntimeException e) {
            java.util.Arrays.fill(f.sp, Double.POSITIVE_INFINITY);
            java.util.Arrays.fill(f.link, Double.POSITIVE_INFINITY);
        } finally {
            c.resetOutsideBelief();
            c.resetLocalBelief();
        }
        sc.sp = f.sp;
        sc.link = f.link;
        return sc;
    }

    public static Result run(Solver cp) {
        long t0 = System.currentTimeMillis();
        // the front end posts without reaching the fixpoint and the search
        // propagates at its root; the probe must see the same domains, or a
        // value held by a bound variable still counts in its neighbours'
        // domains (and breaks the row-stochastic assumption of the Soules bound)
        cp.fixPoint();
        Result res = new Result();
        Belief rep = cp.getBeliefRep();
        IdentityHashMap<IntVar, Integer> idx = new IdentityHashMap<>();
        List<IntVar> vars = new ArrayList<>();
        List<Fac> facs = new ArrayList<>();
        Iterator<Constraint> it = cp.getConstraints().iterator();
        while (it.hasNext()) {
            Constraint c0 = it.next();
            if (!c0.isActive() || !c0.bpParticipant() || !(c0 instanceof AbstractConstraint)) continue;
            AbstractConstraint c = (AbstractConstraint) c0;
            IntVar[] scope = c.getScope();
            // distinct unbound base variables, first position of each
            int[] pos = new int[scope.length];
            int k = 0;
            IdentityHashMap<IntVar, Boolean> seen = new IdentityHashMap<>();
            for (int i = 0; i < scope.length; i++) {
                if (scope[i].isBound()) continue;
                if (seen.put(scope[i].getBaseVar(), Boolean.TRUE) != null) continue;
                pos[k++] = i;
            }
            if (k == 0) continue;
            res.factors++;
            Fac f = new Fac();
            f.var = new int[k];
            f.sp = new double[k];
            f.link = new double[k];
            for (int t = 0; t < k; t++) {
                IntVar b = scope[pos[t]].getBaseVar();
                Integer id = idx.get(b);
                if (id == null) {
                    id = vars.size();
                    idx.put(b, id);
                    vars.add(b);
                }
                f.var[t] = id;
            }
            facs.add(f);
            boolean overBudget = System.currentTimeMillis() - t0 > BUDGET_MS;
            try {
                measure(c, scope, pos, k, f, rep, res, overBudget);
            } catch (RuntimeException e) {
                res.failed++;
                if (DUMP) { System.err.println("c probe failed on " + c.getClass().getSimpleName() + ": " + e); for (int q = 0; q < Math.min(4, e.getStackTrace().length); q++) System.err.println("c   at " + e.getStackTrace()[q]); }
                java.util.Arrays.fill(f.sp, 0.0);
                java.util.Arrays.fill(f.link, Double.POSITIVE_INFINITY);
            } finally {
                c.resetOutsideBelief();
                c.resetLocalBelief();
            }
            boolean prod = false;
            for (int t = 0; t < k; t++) {
                prod |= f.sp[t] >= PRODUCER_SP;
                if (k > 1) {
                    res.links++;
                    if (f.link[t] < LINK_MIN) res.weakLinks++;
                }
            }
            if (prod) res.producers++;
            if (DUMP) {
                double maxSp = 0, minL = Double.MAX_VALUE, maxL = 0;
                double[] ls = f.link.clone();
                java.util.Arrays.sort(ls);
                for (int t = 0; t < k; t++) maxSp = Math.max(maxSp, f.sp[t]);
                System.err.printf(java.util.Locale.ROOT, "c probe %s k=%d maxSP=%.4f link min/med/max=%.3f/%.3f/%.3f%n",
                        c.getClass().getSimpleName(), k, maxSp, ls[0], ls[k / 2], ls[k - 1]);
            }
        }
        propagateReach(vars, facs, res);
        res.ms = System.currentTimeMillis() - t0;
        return res;
    }

    private static void measure(AbstractConstraint c, IntVar[] scope, int[] pos, int k, Fac f,
                                Belief rep, Result res, boolean overBudget) {
        int[][] vals = new int[k][];
        double[][] p = new double[k][];
        c.resetOutsideBelief();
        c.updateBelief();
        for (int t = 0; t < k; t++) {
            IntVar x = scope[pos[t]];
            int[] buf = new int[x.size()];
            int n = x.fillArray(buf);
            vals[t] = java.util.Arrays.copyOf(buf, n);
            p[t] = message(c, pos[t], vals[t], rep);
            if (p[t] == null) throw new IllegalStateException("no support");
            f.sp[t] = Math.log(n) - entropy(p[t]);
        }
        if (k == 1) return;
        if (overBudget) {
            res.unconditioned++;
            java.util.Arrays.fill(f.link, Double.POSITIVE_INFINITY);
            return;
        }
        java.util.Random rnd = new java.util.Random(RNG_SEED + res.factors);
        for (int d = 0; d < DRAWS; d++) {
            c.resetOutsideBelief();
            for (int t = 0; t < k; t++) {
                double[] in = randomMessage(vals[t].length, RHO * Math.log(vals[t].length), rnd);
                for (int j = 0; j < in.length; j++)
                    c.setOutsideBelief(pos[t], vals[t][j], rep.std2rep(in[j]));
            }
            c.updateBelief();
            res.transferCalls++;
            for (int t = 0; t < k; t++) {
                double[] m = message(c, pos[t], vals[t], rep);
                if (m == null) continue;
                f.link[t] += kl(m, p[t]) / (RHO * Math.log(vals[t].length)) / DRAWS;
            }
        }
    }

    static final long RNG_SEED = Long.getLong("minicpbp.gate.seed", 12345L);

    /** softmax of Gaussian logits, temperature set by bisection so that KL from uniform = s */
    static double[] randomMessage(int n, double s, java.util.Random rnd) {
        double[] g = new double[n];
        for (int j = 0; j < n; j++) g[j] = rnd.nextGaussian();
        double target = Math.min(s, 0.9 * Math.log(n));
        double lo = 0, hi = 1;
        while (Math.log(n) - entropy(softmax(g, hi)) < target && hi < 1e4) hi *= 2;
        for (int it = 0; it < 50; it++) {
            double mid = (lo + hi) / 2;
            if (Math.log(n) - entropy(softmax(g, mid)) < target) lo = mid;
            else hi = mid;
        }
        return softmax(g, hi);
    }

    private static double[] softmax(double[] g, double beta) {
        double mx = Double.NEGATIVE_INFINITY;
        for (double v : g) mx = Math.max(mx, v);
        double[] q = new double[g.length];
        double z = 0;
        for (int j = 0; j < g.length; j++) z += q[j] = Math.exp(beta * (g[j] - mx));
        for (int j = 0; j < g.length; j++) q[j] /= z;
        return q;
    }

    private static double kl(double[] p, double[] q) {
        double d = 0;
        for (int j = 0; j < p.length; j++)
            if (p[j] > 0) d += p[j] * Math.log(p[j] / Math.max(q[j], 1e-300));
        return d;
    }

    /** the factor's normalized message to scope position i, or null if it is zero */
    private static double[] message(AbstractConstraint c, int i, int[] vals, Belief rep) {
        double[] q = new double[vals.length];
        double z = 0;
        for (int j = 0; j < vals.length; j++) {
            double v = rep.rep2std(c.localBelief(i, vals[j]));
            if (!(v > 0)) v = 0; // negative, NaN
            q[j] = v;
            z += v;
        }
        if (!(z > 0) || Double.isInfinite(z)) return null;
        for (int j = 0; j < q.length; j++) q[j] /= z;
        return q;
    }

    private static double entropy(double[] p) {
        double h = 0;
        for (double v : p) if (v > 0) h -= v * Math.log(v);
        return h;
    }

    /** max-product path strength from producers, over non-weak links */
    private static void propagateReach(List<IntVar> vars, List<Fac> facs, Result res) {
        int nv = vars.size();
        // incidence: variable -> (factor, position)
        int[] deg = new int[nv + 1];
        for (Fac f : facs) for (int v : f.var) deg[v + 1]++;
        for (int v = 0; v < nv; v++) deg[v + 1] += deg[v];
        int[] incF = new int[deg[nv]];
        int[] fill = java.util.Arrays.copyOf(deg, nv);
        for (int fi = 0; fi < facs.size(); fi++)
            for (int v : facs.get(fi).var) incF[fill[v]++] = fi;
        double[] s = new double[nv];
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(b[0], a[0]));
        for (Fac f : facs)
            for (int t = 0; t < f.var.length; t++)
                if (f.sp[t] >= PRODUCER_SP && s[f.var[t]] < 1) {
                    s[f.var[t]] = 1;
                    pq.add(new double[]{1, f.var[t]});
                }
        boolean[] done = new boolean[nv];
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            int v = (int) top[1];
            if (done[v]) continue;
            done[v] = true;
            for (int e = deg[v]; e < deg[v + 1]; e++) {
                Fac g = facs.get(incF[e]);
                for (int t = 0; t < g.var.length; t++) {
                    int y = g.var[t];
                    if (y == v || done[y] || g.link[t] < LINK_MIN) continue;
                    double cand = s[v] * Math.min(1.0, g.link[t]);
                    if (cand >= REACH_MIN && cand > s[y]) {
                        s[y] = cand;
                        pq.add(new double[]{cand, y});
                    }
                }
            }
        }
        for (int v = 0; v < nv; v++)
            if (s[v] > 0) res.reach.put(vars.get(v), s[v]);
    }
}
