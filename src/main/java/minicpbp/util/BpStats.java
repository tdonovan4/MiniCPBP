package minicpbp.util;

public class BpStats {
    private int newIncomingVarMsgsSum = 0;
    private double incomingVarMsgsUsageEfficiencySum = 0.0;
    private int unpropagatedOutgoingConMsgsSum = 0;
    private double outgoingConMsgsPropagationEfficiencySum = 0.0;
    private int numUnpropagatedLocalBeliefUpdates = 0;
    private int freeVarsSum = 0;
    private int numLocalBeliefUpdates = 0;

    /**
     * Records statistics for a local belief update performed by a constraint.
     *
     * @param newIncomingVarMsgs the number of incoming variable-to-constraint messages that changed/were
     *                            repropagated for this update
     * @param unpropagatedOutgoingConMsgs the number of outgoing constraint-to-variable messages that were not
     *                                    propagated for this update
     * @param effectiveArity the number of free variables in the constraint at the time of this update
     */
    public void recordLocalBeliefUpdate(int newIncomingVarMsgs, int unpropagatedOutgoingConMsgs, int effectiveArity) {
        newIncomingVarMsgsSum += newIncomingVarMsgs;
        incomingVarMsgsUsageEfficiencySum += (double) newIncomingVarMsgs / effectiveArity;
        unpropagatedOutgoingConMsgsSum += unpropagatedOutgoingConMsgs;
        outgoingConMsgsPropagationEfficiencySum += (double) (effectiveArity - unpropagatedOutgoingConMsgs) / effectiveArity;
        freeVarsSum += effectiveArity;
        numLocalBeliefUpdates++;

        if (unpropagatedOutgoingConMsgsSum == effectiveArity) {
            numUnpropagatedLocalBeliefUpdates += 1;
        }
    }

    /**
     * The average number of incoming variable-to-constraint messages that changed/were repropagated for
     * each local belief update, across all constraints.
     * @return the average number of new incoming variable messages per update
     */
    public double avgNewIncomingVarMsgs() {
        return (double) newIncomingVarMsgsSum / numLocalBeliefUpdates;
    }

    /**
     * The average efficiency of incoming variable-to-constraint message usage across all local belief
     * updates and all constraints. The efficiency of an update is the ratio of new incoming messages to all
     * incoming messages.
     * @return the average incoming variable message usage efficiency
     */
    public double avgIncomingVarMsgsUsageEfficiency() {
        return incomingVarMsgsUsageEfficiencySum / numLocalBeliefUpdates;
    }

    /**
     * The average number of outgoing constraint-to-variable messages that had not been propagated for
     * each local belief update, across all constraints.
     * @return the average number of unpropagated outgoing constraint messages per update
     */
    public double avgUnpropagatedOutgoingConMsgs() {
        return (double) unpropagatedOutgoingConMsgsSum / numLocalBeliefUpdates;
    }

    /**
     * The average efficiency of outgoing constraint-to-variable message propagation across all local belief
     * updates and all constraints. The efficiency of an update is the ratio of propagated outgoing messages to all
     * outgoing messages.
     * @return the average outgoing constraint message propagation efficiency
     */
    public double avgOutgoingConMsgsPropagationEfficiency() {
        return outgoingConMsgsPropagationEfficiencySum / numLocalBeliefUpdates;
    }

    /**
     * The number of local belief updates for which no outgoing constraint-to-variable message was propagated,
     * across all constraints.
     *
     * @return the number of local belief updates without a propagated outgoing message
     */
    public int numUnpropagatedLocalBeliefUpdates() {
        return numUnpropagatedLocalBeliefUpdates;
    }

    /**
     * Computes the average effective arity at the time of local belief updates, across all constraints.
     * @return the average effective arity per update
     */
    public double avgEffectiveArity() {
        return (double) freeVarsSum / numLocalBeliefUpdates;
    }

    @Override
    public String toString() {
        return "BP stats\n" +
                "avgNewIncomingVarMsgs: " + avgNewIncomingVarMsgs() + "\n" +
                "avgIncomingVarMsgsUsageEfficiency: " + avgIncomingVarMsgsUsageEfficiency() + "\n" +
                "avgUnpropagatedOutgoingConMsgs: " + avgUnpropagatedOutgoingConMsgs() + "\n" +
                "avgOutgoingConMsgsPropagationEfficiency: " + avgOutgoingConMsgsPropagationEfficiency() + "\n" +
                "numUnpropagatedLocalBeliefUpdates: " + numUnpropagatedLocalBeliefUpdates() + "\n" +
                "avgEffectiveArity: " + avgEffectiveArity();
    }
}
