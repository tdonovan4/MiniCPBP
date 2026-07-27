package minicpbp.util;

import minicpbp.engine.core.Constraint;
import minicpbp.engine.core.IntVar;

import java.util.*;

public class ResidualPQ {
    // Private type to hold message data
    private static class Message {
        public IntVar x;
        public Constraint c;
        public boolean isFromVarToConstraint;

        public Message(IntVar x, Constraint c, boolean b) {
            this.x = x;
            this.c = c;
            this.isFromVarToConstraint = b;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Message message = (Message) o;
            return isFromVarToConstraint == message.isFromVarToConstraint && Objects.equals(x, message.x) && Objects.equals(c, message.c);
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, c, isFromVarToConstraint);
        }
    }

    IndexedMaxPQ<Double> pq;
    HashMap<Message, Integer> indexMap;
    Message[] reverseIndexMap;

    public ResidualPQ() {
        pq = new IndexedMaxPQ<>(20);
        indexMap = new HashMap<>();
        reverseIndexMap = new Message[20];
    }

    public void setResidual(IntVar x, Constraint c, double r) {
        Message key = new Message(x, c, true);
        setResidual(key, r);
    }

    public void setResidual(Constraint c, IntVar x, double r) {
        Message key = new Message(x, c, false);
        setResidual(key, r);
    }

    public Double getResidual(IntVar x, Constraint c) {
        Message key = new Message(x, c, true);
        return getResidual(key);
    }

    public Double getResidual(Constraint c, IntVar x) {
        Message key = new Message(x, c, false);
        return getResidual(key);
    }

    public boolean isEmpty() {
        return indexMap.isEmpty();
    }

    public interface Residual {
        Object from();
        Object to();
        double residual();
    }

    private static class ResidualImpl<F, T> implements Residual {
        F from;
        T to;
        double residual;

        public ResidualImpl(F from, T to, double residual) {
            this.from = from;
            this.to = to;
            this.residual = residual;
        }

        public F from() {
            return from;
        }

        public T to() {
            return to;
        }

        public double residual() {
            return residual;
        }
    }

    public static class VarToConstraintResidual extends ResidualImpl<IntVar, Constraint> implements Residual {
        public VarToConstraintResidual(IntVar from, Constraint to, double residual) {
            super(from, to, residual);
        }
    }

    public static class ConstraintToVarResidual extends ResidualImpl<Constraint, IntVar> implements Residual {
        public ConstraintToVarResidual(Constraint from, IntVar to, double residual) {
            super(from, to, residual);
        }
    }

    public Residual maxResidual() {
        int index = pq.topIndex();
        double r = pq.topKey();
        Message entry = reverseIndexMap[index];
        if (entry.isFromVarToConstraint) {
            return new VarToConstraintResidual(entry.x, entry.c, r);
        } else {
            return new ConstraintToVarResidual(entry.c, entry.x, r);
        }
    }

    public void reset() {
        pq.clear();
        indexMap.clear();
        Arrays.fill(reverseIndexMap, null);
    }

    public int size() {
        return indexMap.size();
    }

    private void setResidual(Message key, double r) {
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

    private Double getResidual(Message key) {
        Integer index = indexMap.get(key);
        if (index == null) {
            return null;
        } else {
            return pq.get(index);
        }
    }

    private void resize(int capacity) {
        Message[] tempReverseIndexMap = new Message[capacity + 1];
        System.arraycopy(reverseIndexMap, 0, tempReverseIndexMap, 0, Math.min(reverseIndexMap.length, tempReverseIndexMap.length));
        this.reverseIndexMap = tempReverseIndexMap;
    }
}
