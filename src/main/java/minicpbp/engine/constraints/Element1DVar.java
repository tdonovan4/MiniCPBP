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
 */

package minicpbp.engine.constraints;

import minicpbp.engine.core.AbstractConstraint;
import minicpbp.engine.core.IntVar;
import static minicpbp.cp.Factory.equal;
import minicpbp.util.exception.NotImplementedException;

public class Element1DVar extends AbstractConstraint {

    private final IntVar[] array;
    private final IntVar y;
    private final IntVar z;
    private final IntVar[] vars;

    private final int[] yValues;
    private double[] weighted; // b_y(k)·s_k per position of yValues (z free)
    private double[] suffix;   // suffix sums of weighted
    private IntVar supMin;
    private IntVar supMax;
    private int zMin;
    private int zMax;
    

    public Element1DVar(IntVar[] array, IntVar y, IntVar z, IntVar[] vars) {
        super(y.getSolver(), vars);
	    setName("Element1DVar");
        this.array = array;
        this.y = y;
        this.z = z;
        this.vars = vars;

        yValues = new int[y.size()];

        if (z.isBound())
            setExactWCounting(true);
        else
            setExactWCounting(false);
    }

    @Override
    public void post() {
        y.removeBelow(0);
        y.removeAbove(array.length - 1);

        if (z.isBound()) { // special case, important for inverse constraint
            for (IntVar t : array) {
                t.propagateOnDomainChange(this);
            }
            y.propagateOnDomainChange(this);
        }
        else {
            for (IntVar t : array) {
                t.propagateOnBoundChange(this);
            }
            y.propagateOnDomainChange(this);
            z.propagateOnBoundChange(this);
        }

        propagate();
    }

    @Override
    public void propagate() {
        if (z.isBound()) { // special case, important for inverse constraint
            filterYwhenZbound();
            if (y.isBound()) {
                array[y.min()].assign(z.min());
                setActive(false);
            }
        }
        else if (y.isBound()) {
            y.getSolver().post(equal(z,array[y.min()]));
            setActive(false);
        }
        else {
            zMin = z.min();
            zMax = z.max();
            filterY();
            if (y.isBound()) {
                y.getSolver().post(equal(z,array[y.min()]));
                setActive(false);
            }
            else {
                z.removeBelow(supMin.min());
                z.removeAbove(supMax.max());
            }
        }

    }

    private void filterY() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int i = y.fillArray(yValues);
        while (i > 0) {
            i -= 1;
            int id = yValues[i];
            IntVar tVar = array[id];
            int tMin = tVar.min();
            int tMax = tVar.max();
            if (tMax < zMin || tMin > zMax) {
                y.remove(id);
            } else {
                if (tMin < min) {
                    min = tMin;
                    supMin = tVar;
                }
                if (tMax > max) {
                    max = tMax;
                    supMax = tVar;
                }
            }
        }
    }

    private void filterYwhenZbound() {
        int i = y.fillArray(yValues);
        while (i > 0) {
            i -= 1;
            int id = yValues[i];
            if (!array[id].contains(z.min())) {
                y.remove(id);
            }
        }
    }

    public void updateBelief() {
        if (z.isBound()) { // special case: compute exact beliefs
            double cumul = beliefRep.zero();
            // for y
            int nVal = y.fillArray(domainValues);
            for (int j = 0; j < nVal; j++) {
                int v = domainValues[j];
                setLocalBelief(array.length /* i.e. y */, v, outsideBelief(v, z.min()));
                cumul = beliefRep.add(cumul, beliefRep.multiply(outsideBelief(v,z.min()),
                        outsideBelief(array.length,v)));
            }
            // for array
            for (int i = 0; i < array.length; i++) {
                if (y.contains(i)) {
                    double cumulOthers = beliefRep.subtract(cumul, beliefRep.multiply(outsideBelief(i,z.min()), outsideBelief(array.length,i)));
                    nVal = array[i].fillArray(domainValues);
                    for (int j = 0; j < nVal; j++) {
                        int v = domainValues[j];
                        if (v == z.min()) {
                            setLocalBelief(i, v, beliefRep.add(cumulOthers, outsideBelief(array.length, i)));
                        } else {
                            setLocalBelief(i, v, cumulOthers);
                        }
                    }
                }
                else { // its values equally support every solution
                    nVal = array[i].fillArray(domainValues);
                    for (int j = 0; j < nVal; j++) {
                        setLocalBelief(i, domainValues[j], beliefRep.one()); // will be normalized
                    }
                }
            }
        }
        else
            updateBeliefZfree();
    }

    /**
     * Exact messages for z = array[y] with z free, in O(n·d).
     * With s_k = sum over v in D(array[k]) ∩ D(z) of b_array[k](v)·b_z(v):
     * to y, m(k) = s_k; to z, m(v) = sum over k of b_y(k)·b_array[k](v);
     * to array[i] with i in D(y), m(v) = b_y(i)·b_z(v) + sum over k ≠ i of b_y(k)·s_k,
     * uniform otherwise. The sums over k ≠ i use prefix and suffix sums rather than
     * a subtraction, which loses precision in the log representation.
     */
    private void updateBeliefZfree() {
        final int yPos = array.length, zPos = array.length + 1;
        if (weighted == null) {
            weighted = new double[yValues.length];
            suffix = new double[yValues.length + 1];
        }
        int nY = y.fillArray(yValues);
        // to y: s_k
        for (int a = 0; a < nY; a++) {
            int k = yValues[a];
            double s = beliefRep.zero();
            int nVal = array[k].fillArray(domainValues);
            for (int j = 0; j < nVal; j++) {
                int v = domainValues[j];
                if (z.contains(v))
                    s = beliefRep.add(s, beliefRep.multiply(outsideBelief(k, v), outsideBelief(zPos, v)));
            }
            setLocalBelief(yPos, k, s);
            weighted[a] = beliefRep.multiply(outsideBelief(yPos, k), s);
        }
        // to z
        int nZ = z.fillArray(domainValues);
        for (int j = 0; j < nZ; j++) {
            int v = domainValues[j];
            double m = beliefRep.zero();
            for (int a = 0; a < nY; a++) {
                int k = yValues[a];
                if (array[k].contains(v))
                    m = beliefRep.add(m, beliefRep.multiply(outsideBelief(yPos, k), outsideBelief(k, v)));
            }
            setLocalBelief(zPos, v, m);
        }
        // to array[i], i in D(y)
        suffix[nY] = beliefRep.zero();
        for (int a = nY - 1; a >= 0; a--)
            suffix[a] = beliefRep.add(suffix[a + 1], weighted[a]);
        double prefix = beliefRep.zero();
        for (int a = 0; a < nY; a++) {
            int i = yValues[a];
            double others = beliefRep.add(prefix, suffix[a + 1]);
            int nVal = array[i].fillArray(domainValues);
            for (int j = 0; j < nVal; j++) {
                int v = domainValues[j];
                setLocalBelief(i, v, z.contains(v)
                        ? beliefRep.add(others, beliefRep.multiply(outsideBelief(yPos, i), outsideBelief(zPos, v)))
                        : others);
            }
            prefix = beliefRep.add(prefix, weighted[a]);
        }
        // array[i] with i outside D(y): its values equally support every solution
        for (int i = 0; i < array.length; i++) {
            if (!y.contains(i)) {
                int nVal = array[i].fillArray(domainValues);
                for (int j = 0; j < nVal; j++)
                    setLocalBelief(i, domainValues[j], beliefRep.one()); // will be normalized
            }
        }
    }
}
