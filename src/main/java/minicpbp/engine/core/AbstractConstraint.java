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

import minicpbp.state.StateBool;
import minicpbp.state.StateDouble;

import minicpbp.util.Belief;

import minicpbp.util.ResidualPQ;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Abstract class most of the constraints
 * should extend.
 */
public abstract class AbstractConstraint implements Constraint {

    private String name;
    /**
     * The solver in which the constraint is created
     */
    private final Solver cp;
    private boolean scheduled = false;
    private final StateBool active;

    private StateDouble[][] localBelief;
    private double[][] prevLocalBelief; // needed for RBP
    private double[][] outsideBelief;
    private StateDouble[][] prevOutsideBelief; // needed for message damping and RBP
    private double weight; // an optional nonnegative weight applied to the constraint's local belief
    protected Belief beliefRep;
    private int[] ofs;
    protected IntVar[] vars; // all the variables in the scope of the constraint
    private int maxDomainSize;
    protected int[] domainValues; // an array large enough to hold any domain of vars
    protected double[] beliefValues; // an auxiliary array as large as domainValues
    private boolean exactWCounting = false;
    private boolean updateBeliefWarningPrinted = false;
    private boolean weightedCountingWarningPrinted = false;
    // For WDBP
    private int[] inboundPropagationCount;
    private int[] outboundPropagationCount;

    private int failureCount;

    public AbstractConstraint(Solver cp, IntVar[] vars) {
        this.cp = cp;
        active = cp.getStateManager().makeStateBool(true);
        beliefRep = cp.getBeliefRep();
        this.vars = new IntVar[vars.length];
        System.arraycopy(vars,0,this.vars,0,vars.length); // required if constraint sets up offseted vars in the same array
        switch (cp.getWeighingScheme()) {
            case SAME:
                weight = 1.0;
                break;
            case ARITY:
                // will be set in MiniCP.computeMinArity()
                break;
        }
        localBelief = new StateDouble[vars.length][];
        prevLocalBelief = new double[vars.length][];
        ofs = new int[vars.length];
        outsideBelief = new double[vars.length][];
        prevOutsideBelief = new StateDouble[vars.length][];

        maxDomainSize = 0;
        for (int i = 0; i < vars.length; i++) {
            vars[i].registerConstraint(this);
            ofs[i] = vars[i].min();
            localBelief[i] = new StateDouble[vars[i].max() - vars[i].min() + 1];
            prevLocalBelief[i] = new double[localBelief[i].length];
            outsideBelief[i] = new double[vars[i].max() - vars[i].min() + 1];
            prevOutsideBelief[i] = new StateDouble[outsideBelief[i].length];
            for (int j = 0; j < localBelief[i].length; j++) {
                localBelief[i][j] = cp.getStateManager().makeStateDouble(beliefRep.one()); // no belief yet; initialized to ONE (certainly true) in order to retrieve the first var-to-constraint msg correctly
                prevLocalBelief[i][j] = beliefRep.one();
                prevOutsideBelief[i][j] = cp.getStateManager().makeStateDouble(beliefRep.zero()); // arbitrary
            }
            maxDomainSize = Math.max(maxDomainSize, vars[i].max() - vars[i].min() + 1);
        }
        domainValues = new int[maxDomainSize];
        beliefValues = new double[maxDomainSize];
        failureCount = 0;
    }

    public int arity() {
        return vars.length;
    }

    public int dynamicArity() {
        int k = 0;
        for (int i = 0; i < vars.length; i++) {
            if (!vars[i].isBound()) k++;
        }
        return k;
    }

    /*public double getWeight() {
		switch(cp.getWeighingScheme()) {
			case SAME:
				return 1.0;
			case ARITY:
				// assumes all model variables have already been declared/registered
				return 1.0 + ((double) vars.length - (double) cp.minArity())/ ((double) cp.getVariables().size());
			case ANTI:
                return 1.0 - ((double) vars.length - (double) cp.minArity()) / ((double) cp.getVariables().size());
			default:
				throw new NotImplementedException();
			}

	}*/

	public void incrementFailureCount() {
		failureCount+=1;
	}

	public int getFailureCount() {
		return failureCount;
	}

    public void post() {}

    public Solver getSolver() {
        return cp;
    }

    public void propagate() {}

    public void setScheduled(boolean scheduled) {
        this.scheduled = scheduled;
    }

    public boolean isScheduled() {
        return scheduled;
    }

    public void setActive(boolean active) {
        this.active.setValue(active);
    }

    public boolean isActive() {
        return active.value();
    }

    protected void setExactWCounting(boolean exact) {
        this.exactWCounting = exact;
    }

    protected boolean isExactWCounting() {
        return exactWCounting;
    }

    public void setWeight(double w) {
        assert w >= 0 : "c A constraint's weight should be nonnegative";
        weight = w;
    }

    public double weight() {
        return weight;
    }

    protected double localBelief(int i, int val) {
        return localBelief[i][val - ofs[i]].value();
    }

    protected double setLocalBelief(int i, int val, double b) {
        return localBelief[i][val - ofs[i]].setValue(b);
    }

    protected double prevLocalBelief(int i, int val) {
        return prevLocalBelief[i][val - ofs[i]];
    }

    protected double setPrevLocalBelief(int i, int val, double b) {
        prevLocalBelief[i][val - ofs[i]] = b;
        return b;
    }

    protected double outsideBelief(int i, int val) {
        return outsideBelief[i][val - ofs[i]];
    }

    protected double setOutsideBelief(int i, int val, double b) {
        outsideBelief[i][val - ofs[i]] = b;
        return b;
    }

    protected double prevOutsideBelief(int i, int val) {
        return prevOutsideBelief[i][val - ofs[i]].value();
    }

    protected double setPrevOutsideBelief(int i, int val, double b) {
        return prevOutsideBelief[i][val - ofs[i]].setValue(b);
    }

    interface getBelief {
        double get(int i, int val);
    }

    interface setBelief {
        double set(int i, int val, double b);
    }

    private void normalizeBelief(int i, getBelief f1, setBelief f2) {
        int s = vars[i].fillArray(domainValues);
        if (s == 1) { // variable is bound
            f2.set(i, domainValues[0], beliefRep.one());
            return;
        }
        for (int j = 0; j < s; j++) {
            beliefValues[j] = f1.get(i, domainValues[j]);
        }
        double normalizingConstant = beliefRep.summation(beliefValues, s);
        if (beliefRep.isZero(normalizingConstant)) // temporary state of a soon-to-be-empty domain
            return;
        for (int j = 0; j < s; j++) {
            int val = domainValues[j];
            f2.set(i, val, beliefRep.divide(f1.get(i, val), normalizingConstant));
            assert f1.get(i, val) <= beliefRep.one() && f1.get(i, val) >= beliefRep.zero() : "c Should be normalized! f1.get(i,val) = " + f1.get(i, val);
        }
    }

    public void resetLocalBelief() {
        for (int i = 0; i < vars.length; i++) {
            int s = vars[i].fillArray(domainValues);
            double uniform = beliefRep.divide(beliefRep.one(),(double) s);
            for (int j = 0; j < s; j++) {
                setLocalBelief(i, domainValues[j], uniform);
                setPrevLocalBelief(i, domainValues[j], uniform);
            }
        }
    }
    public void resetOutsideBelief() {
        for (int i = 0; i < vars.length; i++) {
            int s = vars[i].fillArray(domainValues);
            double uniform = beliefRep.divide(beliefRep.one(),(double) s);
            for (int j = 0; j < s; j++) {
                setOutsideBelief(i, domainValues[j], uniform);
                setPrevOutsideBelief(i, domainValues[j], uniform);
            }
        }
    }
    public void resetPropagationCounts() {
        this.inboundPropagationCount = new int[vars.length];
        this.outboundPropagationCount = new int[vars.length];
    }

    private void dampenMessages(int i) {
        double lambda = beliefRep.std2rep(cp.dampingFactor());
        double oneMinusLambda = beliefRep.complement(lambda);
        int s = vars[i].fillArray(domainValues);
        for (int j = 0; j < s; j++) {
            int val = domainValues[j];
            setOutsideBelief(i, val, beliefRep.add(beliefRep.multiply(lambda, outsideBelief(i, val)), beliefRep.multiply(oneMinusLambda, prevOutsideBelief(i, val))));
        }
        normalizeBelief(i, (j, val) -> outsideBelief(j, val), (j, val, b) -> setOutsideBelief(j, val, b));
    }

    public void receiveMessages() {
        for (int i = 0; i < vars.length; i++) {
            receiveMessage(i);
        }
    }

    public void receiveMessage(IntVar x) {
        for (int i = 0; i < vars.length; i++) {
            // Identity comparison is used to identify the exact object
            if (vars[i].getConcreteVar() == x.getConcreteVar()) {
                receiveMessage(i);
                // Can't break because the variable can be present with one or multiple views
            }
        }
    }

    public void sendMessages() {
        updateBelief();
        for (int i = 0; i < vars.length; i++) {
            sendMessage(i, true);
        }
    }

    public void sendMessage(IntVar x) {
        for (int i = 0; i < vars.length; i++) {
            // Identity comparison is used to identify the exact object
            if (vars[i] == x) {
                sendMessage(i, false);
                break;
            }
        }
    }

    public void resendMessage(IntVar x, int v) {
        for (int i = 0; i < vars.length; i++) {
            // Identity comparison is used to identify the exact object
            if (vars[i].getConcreteVar() == x.getConcreteVar()) {
                if (cp.getBpMode().isAsync()) {
                    vars[i].receiveMessage(v, beliefRep.pow(prevLocalBelief(i, v), this.weight));
                } else {
                    vars[i].receiveMessage(v, beliefRep.pow(localBelief(i, v), this.weight));
                }
                // Can't break because the variable can be present with one or multiple views
            }
        }
    }

    public void updateVarsResiduals() {
        updateBelief();
        if (cp.getTraceBPMsgsFlag()) {
            System.out.println("Recomputing local belief and updating outbound messages");
        }
        for (int i = 0; i < vars.length; i++) {
            if (!vars[i].isBound()) {
                normalizeBelief(i, this::localBelief, this::setLocalBelief);
                int s = vars[i].fillArray(domainValues);
                for (int j = 0; j < s; j++) {
                    int val = domainValues[j];
                    // This outside belief was used to update the local belief, mark it as up to date
                    setPrevOutsideBelief(i, val, outsideBelief(i, val));
                }

                int finalI = i;
                Iterator<Double> localBeliefIterator = Arrays.stream(domainValues).limit(s)
                        .mapToDouble((val) -> beliefRep.pow(localBelief(finalI, val), this.weight)).iterator();
                Iterator<Double> prevLocalBeliefIterator = Arrays.stream(domainValues).limit(s)
                        .mapToDouble((val) -> beliefRep.pow(prevLocalBelief(finalI, val), this.weight)).iterator();
                double outboundResidual = residual(localBeliefIterator, prevLocalBeliefIterator, outboundPropagationCount[i]);

                if (cp.getTraceBPMsgsFlag()) {
                    System.out.println("  " + this.getName() + "->" + vars[i].getName() + " updated residual: " + outboundResidual);
                }
                ResidualPQ residualPQ = cp.getResidualPQ();
                Double inboundResidual = residualPQ.getResidual(vars[i], this);
                if (inboundResidual == null || inboundResidual > 0) {
                    // Since the updated outside belief was used, we can mark the message from the variable as up to date
                    cp.getResidualPQ().setResidual(vars[i], this, 0);
                    // Should we only increment the message with the max residual instead?
                    inboundPropagationCount[i]++;
                }
                // Mark the message to the variable as outdated
                cp.getResidualPQ().setResidual(this, vars[i], outboundResidual);
            }
        }
    }


    /**
     * Returns the semantic loss, computed using weighted model counting.
     * To be defined in the actual constraint.
     * <p>
     * Default behaviour: returns zero (tautology constraint)
     */
    public double loss() {
        receiveMessagesWCounting(); // collect pmfs over the domains of the variables in the scope of the constraint
        return -Math.log(beliefRep.rep2std(weightedCounting()));
    }

    /**
     * Computes gradients for variable/value pairs from the constraints given outside beliefs.
     */
    public void gradients() {
        receiveMessagesWCounting(); // collect pmfs over the domains of the variables in the scope of the constraint
        double wc = beliefRep.rep2std(weightedCounting());
        if (wc == 0) {
            System.out.println("*** Warning! Infinite loss; mitigating by producing some very large gradients ***");
            wc = 1.0E-10;
        }
        updateBelief();
        for (int i = 0; i < vars.length; i++) {
            System.out.println("* "+vars[i].getName());
            normalizeBelief(i, (j, val) -> localBelief(j, val), (j, val, b) -> setLocalBelief(j, val, b));
            int s = vars[i].fillArray(domainValues);
            double sumOverDomain = 0;
            for (int j = 0; j < s; j++) {
                sumOverDomain += localBelief(i, domainValues[j]);
            }
            for (int j = 0; j < s; j++) {
                double gradient = (sumOverDomain - 2.0*localBelief(i, domainValues[j])) / wc;
                System.out.println(domainValues[j]+": "+gradient);
            }
        }
    }

    /**
       * Updates its local belief given the outside beliefs.
       * To be defined in the actual constraint.
       * <p>
       * Default behaviour: uniform belief
       * CAVEAT: may set zero/one beliefs but should not directly remove domain values (only done in sendMessages() if actOnZeroOneBelief flag is set)
       */
    protected void updateBelief() {
        if (!updateBeliefWarningPrinted) {
            if (getName() != null) // do not print warning for unnamed constraint
                System.out.println("c Warning: method updateBelief not implemented yet for " + getName() + " constraint. Using uniform belief instead.");
            updateBeliefWarningPrinted = true;
        }
        for (int i = 0; i < vars.length; i++) {
            for (int j = 0; j < localBelief[i].length; j++) {
                localBelief[i][j].setValue(beliefRep.one()); // will be normalized
            }
        }
    }

    /**
     * Collects messages (outside beliefs) from the variables in its scope.
     * Used to compute the semantic loss and gradients via weighted counting
     */
    public void receiveMessagesWCounting() {
        for (int i = 0; i < vars.length; i++) {
            int s = vars[i].fillArray(domainValues);
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                setOutsideBelief(i, val, vars[i].sendMessage(val, beliefRep.one()));
            }
        }
    }

    /**
     * Optionally computes and sets the marginals of auxiliary variables created in the constraint's implementation.
     * To be optionally defined in the actual constraint.
     * <p>
     * Default behaviour: does nothing
     */
    public void setAuxVarsMarginalsWCounting() {}

    /**
     * Computes and returns the weighted count of solutions (i.e. weighted model counting) given the outside beliefs.
     * To be defined in the actual constraint.
     * !!!IMPORTANT NOTE!!!: the computation may rely on the fact that variables have all their beliefs initialized to beliefRep.one() upon creation (in StateSparseWeightedSet)
     * <p>
     * Default behaviour: returns beliefRep.one() (tautology constraint)
     */
    protected double weightedCounting() {
        if (!weightedCountingWarningPrinted) {
            if (getName() != null) // do not print warning for unnamed constraint
                System.out.println("c Warning: method weightedCounting not implemented yet for " + getName() + " constraint. Returning beliefRep.one() instead.");
            weightedCountingWarningPrinted = true;
        }
        return beliefRep.one();
    }

    @Override
    public String getName() {
        return this.name + hashCode();
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    private void sendMessage(int varIdx, boolean wasLocalBeliefUpdated) {
        if (!vars[varIdx].isBound()) { // if the variable is bound, it is pointless to send a "certainly true" message
            normalizeBelief(varIdx, this::localBelief, this::setLocalBelief);
            int s = vars[varIdx].fillArray(domainValues);
            if (cp.getTraceBPMsgsFlag()) {
                System.out.println(getName() + "->" + vars[varIdx].getName());
                System.out.println("  Msg (old) : " + Arrays.toString(prevLocalBelief[varIdx]));
                System.out.println("  Msg : " + Arrays.toString(localBelief[varIdx]));
            }
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                double localB = localBelief(varIdx, val);
                assert localB <= beliefRep.one() && localB >= beliefRep.zero() : "c Should be normalized! localB = " + localB;
                if (getSolver().actingOnZeroOneBelief() && isExactWCounting()) {
                    if (beliefRep.isZero(localB)) { // no support from this constraint
                        // System.out.println(getName()+".sendMessages(): removing value "+val+" from the domain of "+vars[i].getName()+vars[i].toString()+" because its local belief is ZERO");
                        vars[varIdx].remove(val); // standard domain consistency filtering
                        getSolver().fixPoint();
                        break;
                    } else if (beliefRep.isOne(localB)) { // backbone var for this constraint (and hence for all of them)
                        // System.out.println(getName()+".sendMessages(): assigning value "+val+" from the domain of "+vars[i].getName()+vars[i].toString()+" because its local belief is ONE");
                        vars[varIdx].assign(val);
                        getSolver().fixPoint();
                        break; // all other values in this loop will have been removed from the domain
                    }
                }
                if (!cp.getBpMode().isAsync()){
                    vars[varIdx].receiveMessage(val, beliefRep.pow(localB, this.weight));
                } else {
                    double prevLocalB = prevLocalBelief(varIdx, val);
                    assert prevLocalB <= beliefRep.one() && prevLocalB >= beliefRep.zero() : "c Should be normalized! prevLocalB = " + prevLocalB;

                    // Must override mark local belief as up to date before sending the message because the variable might
                    // need to recompute the variable
                    setPrevLocalBelief(varIdx, val, localBelief(varIdx, val));
                    vars[varIdx].receiveMessage(val, beliefRep.pow(prevLocalB, this.weight), beliefRep.pow(localB, this.weight));
                    if (wasLocalBeliefUpdated) {
                        // This outside belief was used to update the local belief, mark it as up to date
                        setPrevOutsideBelief(varIdx, val, outsideBelief(varIdx, val));
                    }
                }
            }

            if (cp.getBpMode().isAsync()) {
                vars[varIdx].normalizeMarginals();
                outboundPropagationCount[varIdx]++;
                cp.getResidualPQ().setResidual(this, vars[varIdx], 0);
                if (cp.getTraceBPMsgsFlag()) {
                    System.out.println("  Marginal :" + vars[varIdx]);
                }
                if (wasLocalBeliefUpdated) {
                    Double inboundResidual = cp.getResidualPQ().getResidual(vars[varIdx], this);
                    if (inboundResidual == null || inboundResidual > 0) {
                        // Since the updated outside belief was used, we can mark the message from the variable as up to date
                        cp.getResidualPQ().setResidual(vars[varIdx], this, 0);
                        // Should we only increment the message with the max residual instead?
                        inboundPropagationCount[varIdx]++;
                    }
                }
                // Notify other constraints of this update
                for (Iterator<Constraint> it = vars[varIdx].constraints(); it.hasNext(); ) {
                    Constraint c = it.next();
                    if (c.isActive() && c != this) {
                        // Index might be different so pass object instead
                        c.receiveMessage(vars[varIdx]);
                    }
                }
            }
        }
    }

    private void receiveMessage(int varIdx) {
        if (vars[varIdx].isBound()) {
            setOutsideBelief(varIdx, vars[varIdx].min(), beliefRep.one());
        } else {
            int s = vars[varIdx].fillArray(domainValues);
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                double localB;
                if (cp.getBpMode().isAsync()) {
                    // Current local belief might not have been sent to the variable yet
                    localB = prevLocalBelief(varIdx, val);
                } else {
                    localB = localBelief(varIdx, val);
                }
                assert localB <= beliefRep.one() && localB >= beliefRep.zero() : "c Should be normalized! localBelief(i,val) = " + localB;
                setOutsideBelief(varIdx, val, vars[varIdx].sendMessage(val, beliefRep.pow(localB, this.weight)));
            }
            normalizeBelief(varIdx, this::outsideBelief, this::setOutsideBelief);
            if (cp.dampingMessages()) {
                if (cp.prevOutsideBeliefRecorded())
                    dampenMessages(varIdx);
            }

            if (cp.dampingMessages() && !cp.getBpMode().isAsync()) {
                for (int j = 0; j < s; j++) {
                    int val = domainValues[j];
                    setPrevOutsideBelief(varIdx, val, outsideBelief(varIdx, val));
                }
            }

            if (cp.getTraceBPMsgsFlag()) {
                System.out.println(vars[varIdx].getName() + "->" + getName());
                System.out.println("  Msg (old) :" + Arrays.toString(prevOutsideBelief[varIdx]));
                System.out.println("  Msg :" + Arrays.toString(outsideBelief[varIdx]));
            }

            if (cp.getBpMode().isAsync()) {
                double residual = residual(outsideBelief[varIdx], prevOutsideBelief[varIdx], inboundPropagationCount[varIdx]);
                if (cp.getTraceBPMsgsFlag()) {
                    System.out.println("  Residual: " + residual);
                }
                cp.getResidualPQ().setResidual(vars[varIdx], this, residual);
            }
        }
    }

    private double residual(double[] a, StateDouble[] b, int propagationCount) {
        return residual(Arrays.stream(a).iterator(), Arrays.stream(b).map(StateDouble::value).iterator(), propagationCount);
    }

    private double residual(Iterator<Double> a, Iterator<Double> b, int propagationCount) {
        if (propagationCount == 0) {
            return Double.POSITIVE_INFINITY;
        }

        double residual = 0;
        switch (cp.getRbpNorm()) {
            case L1:
                while (a.hasNext() && b.hasNext()) {
                    residual += Math.pow(a.next() - b.next(), 2);
                }
                residual = Math.sqrt(residual);
                break;
            case L2:
                while (a.hasNext() && b.hasNext()) {
                    residual += Math.abs(a.next() - b.next());
                }
                break;
            case LInf:
                while (a.hasNext() && b.hasNext()) {
                    double diff = Math.abs(a.next() - b.next());
                    if (diff > residual) {
                        residual = diff;
                    }
                }
                break;
            default:
                throw new UnsupportedOperationException("Unsupported RBP norm: " + cp.getRbpNorm());
        }
        assert !a.hasNext() && !b.hasNext() : "both sides should have the same number of values";

        if (cp.getBpMode() == Solver.BpMode.WDBP) {
            return residual / propagationCount;
        } else {
            return residual;
        }
    }
}
