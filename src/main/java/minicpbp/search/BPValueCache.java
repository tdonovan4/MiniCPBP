/*
 * mini-cpbp: exact reuse of BP's value choice across LDS passes.
 *
 * Naive LDS re-expands the tree prefix on every pass. A revisited node has the
 * same domains (same decision path, deterministic propagation, and BP never
 * removes a value), so if dom/wdeg selects the same variable there, rooted BP
 * returns the same marginal. The cache keys BP's max-marginal value on
 * (decision path, branching variable) and, installed as the solver's BP gate,
 * skips BP on a hit. dom/wdeg weights grow between passes, so a revisited node
 * may select another variable: that is a miss, and BP runs.
 *
 * The path key is a trailed 64-bit hash of the decisions taken, restored on
 * backtrack. Collisions would only change a value choice, never soundness.
 */

package minicpbp.search;

import minicpbp.engine.core.IntVar;
import minicpbp.state.State;
import minicpbp.state.StateManager;

import java.util.HashMap;
import java.util.function.Predicate;

public final class BPValueCache implements Predicate<IntVar> {

    private final State<Long> path;
    private final HashMap<Long, Integer> values = new HashMap<>();
    public long hits, misses;

    public BPValueCache(StateManager sm) {
        path = sm.makeStateRef(0L);
    }

    private static long mix(long h, long v) {
        long z = h * 0x9E3779B97F4A7C15L + v + 0x632BE59BD9B4E019L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private long key(IntVar x) {
        return mix(path.value(), System.identityHashCode(x.getBaseVar()));
    }

    /** the BP gate: run BP only when the value at this node is not cached */
    @Override
    public boolean test(IntVar x) {
        boolean miss = !values.containsKey(key(x));
        if (miss) misses++;
        else hits++;
        return miss;
    }

    /** the value to branch on: cached, or BP's max marginal, then cached */
    public int value(IntVar x) {
        long k = key(x);
        Integer v = values.get(k);
        if (v != null) return v;
        int m = x.valueWithMaxMarginal();
        values.put(k, m);
        return m;
    }

    /** records a decision on the current path; call inside the child, before propagating */
    public void descend(IntVar x, int v, boolean equal) {
        path.setValue(mix(mix(path.value(), System.identityHashCode(x.getBaseVar())), equal ? v : ~v));
    }

    public String summary() {
        return "cacheHits=" + hits + ",cacheMisses=" + misses + ",cached=" + values.size();
    }
}
