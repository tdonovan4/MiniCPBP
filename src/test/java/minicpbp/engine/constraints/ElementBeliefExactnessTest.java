package minicpbp.engine.constraints;

import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import minicpbp.util.exception.InconsistencyException;
import org.junit.Test;

import java.util.Random;

import static minicpbp.cp.Factory.*;
import static org.junit.Assert.assertEquals;

/**
 * Element1DVar messages with z free (z = array[y]) against exact weighted counting.
 * Each scope variable of the element also appears in one random binary table with a
 * private variable, so the factor graph is a tree, the element receives non-uniform
 * messages, and BP marginals must equal the enumerated ones.
 */
public class ElementBeliefExactnessTest {

    @Test
    public void zFree() {
        int checked = 0;
        for (Solver.PropaMode mode : new Solver.PropaMode[]{Solver.PropaMode.SBP, Solver.PropaMode.BP}) {
            for (int seed = 0; seed < 100; seed++) {
                Random r = new Random(seed);
                int d = 2 + r.nextInt(2); // array length = domain size = d
                int ns = d + 2;           // scope: array[0..d-1], y, z
                int nv = 2 * ns;          // plus one private variable per scope variable
                int[][][] t = new int[ns][][];
                for (int s = 0; s < ns; s++) {
                    t[s] = new int[2 + r.nextInt(2 * d)][2];
                    for (int[] row : t[s]) {
                        row[0] = r.nextInt(d);
                        row[1] = r.nextInt(d);
                    }
                }
                Solver cp = makeSolver();
                cp.setMode(mode);
                IntVar[] x = new IntVar[nv];
                for (int i = 0; i < nv; i++)
                    x[i] = makeIntVar(cp, 0, d - 1);
                IntVar[] array = new IntVar[d];
                System.arraycopy(x, 0, array, 0, d);
                IntVar y = x[d], z = x[d + 1];
                try {
                    cp.post(element(array, y, z));
                    for (int s = 0; s < ns; s++)
                        cp.post(table(new IntVar[]{x[s], x[ns + s]}, t[s]));
                } catch (InconsistencyException e) {
                    continue;
                }
                if (y.isBound() || z.isBound())
                    continue; // the z-free messages are not used then
                cp.setMaxIter(10);
                cp.vanillaBP(10);

                double[][] cnt = new double[nv][d];
                double total = 0;
                int[] v = new int[nv];
                for (int c = 0; c < (int) Math.pow(d, nv); c++) {
                    boolean inDom = true;
                    for (int i = 0, q = c; i < nv; i++, q /= d) {
                        v[i] = q % d;
                        inDom &= x[i].contains(v[i]);
                    }
                    if (!inDom || v[d + 1] != v[v[d]])
                        continue;
                    double w = 1;
                    for (int s = 0; s < ns && w > 0; s++) {
                        int matches = 0;
                        for (int[] row : t[s])
                            if (row[0] == v[s] && row[1] == v[ns + s])
                                matches++;
                        w *= matches;
                    }
                    if (w > 0) {
                        total += w;
                        for (int i = 0; i < nv; i++)
                            cnt[i][v[i]] += w;
                    }
                }
                if (total == 0)
                    continue;
                for (int i = 0; i < nv; i++)
                    for (int val = 0; val < d; val++)
                        if (x[i].contains(val))
                            assertEquals(mode + " seed " + seed + " x" + i + "=" + val,
                                    cnt[i][val] / total, cp.getBeliefRep().rep2std(x[i].marginal(val)), 1e-9);
                checked++;
            }
        }
        org.junit.Assert.assertTrue("too few instances exercised: " + checked, checked > 50);
    }
}
