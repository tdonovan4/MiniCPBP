package minicpbp.util;

import minicpbp.engine.core.Constraint;
import minicpbp.engine.core.IntVar;

import java.util.*;

public class ResidualPQ {
    IndexedMaxPQ<Double> pq;
    HashMap<Map.Entry<IntVar, Constraint>, Integer> indexMap;
    Map.Entry<IntVar, Constraint>[] reverseIndexMap;

    @SuppressWarnings("unchecked")
    public ResidualPQ() {
        pq = new IndexedMaxPQ<>(20);
        indexMap = new HashMap<>();
        reverseIndexMap = (Map.Entry<IntVar, Constraint>[]) new Map.Entry[20];
    }

    public void setResidual(IntVar x, Constraint c, double r) {
        Map.Entry<IntVar, Constraint> key = new AbstractMap.SimpleImmutableEntry<>(x, c);
        Integer index = indexMap.get(key);
        if (index == null) {
            index = pq.insert(r);
            indexMap.put(key, index);
            if (index >= reverseIndexMap.length) {
                resize(reverseIndexMap.length * 2);
            }
            reverseIndexMap[index] = key;
        } else {
            pq.update(index, r);
        }
    }

    public boolean isEmpty() {
        return indexMap.isEmpty();
    }

    public static class Residual {
        IntVar x;
        Constraint c;
        double r;

        private Residual(IntVar x, Constraint c, double r) {
            this.x = x;
            this.c = c;
            this.r = r;
        }

        public IntVar from() {
            return x;
        }

        public Constraint to() {
            return c;
        }

        public double residual() {
            return r;
        }
    }

    public Residual maxResidual() {
        int index = pq.topIndex();
        double r = pq.topKey();
        Map.Entry<IntVar, Constraint> entry = reverseIndexMap[index];
        return new Residual(entry.getKey(), entry.getValue(), r);
    }

    public void reset() {
        pq.clear();
        indexMap.clear();
        Arrays.fill(reverseIndexMap, null);
    }

    @SuppressWarnings("unchecked")
    private void resize(int capacity) {
        Map.Entry<IntVar, Constraint>[] tempReverseIndexMap = (Map.Entry<IntVar, Constraint>[]) new Map.Entry[capacity + 1];
        System.arraycopy(reverseIndexMap, 0, tempReverseIndexMap, 0, Math.min(reverseIndexMap.length, tempReverseIndexMap.length));
        this.reverseIndexMap = tempReverseIndexMap;
    }
}
