package minicpbp.util;

public class BpConfig {
    public BpConfig(int maxIter, BpSchedule bpSchedule, RbpNorm rbpNorm, double minResidual, boolean postBpLocalBeliefPropagation) {
        this.maxIter = maxIter;
        this.bpSchedule = bpSchedule;
        this.rbpNorm = rbpNorm;
        this.minResidual = minResidual;
        this.postBpLocalBeliefPropagation = postBpLocalBeliefPropagation;
    }

    /**
     * The maximum number of BP iteration at every node
     */
    private final int maxIter;
    /**
     * What BP schedule to use
     */
    private final BpSchedule bpSchedule;
    /**
     * What norm to use to calculate the residual between two messages
     */
    private final RbpNorm rbpNorm;
    /**
     * Stop BP iteration if the maximum residual drops below this threshold
     */
    private final double minResidual;
    /**
     * Whether to propagate updated constraint-to-variable messages that have not yet been propagated at the end of the BP iterations.
     * This option is only followed when the BP schedule is async.
     */
    private final boolean postBpLocalBeliefPropagation;

    public int getMaxIter() {
        return maxIter;
    }

    public BpSchedule getBpSchedule() {
        return bpSchedule;
    }

    public RbpNorm getRbpNorm() {
        return rbpNorm;
    }

    public double getMinResidual() {
        return minResidual;
    }

    public boolean isPostBpLocalBeliefPropagation() {
        return postBpLocalBeliefPropagation;
    }

    @SuppressWarnings("UnusedReturnValue")
    public static class BpConfigBuilder {
        private int maxIter = 10;
        private BpSchedule bpSchedule = BpSchedule.Flood;
        private RbpNorm rbpNorm = RbpNorm.LInf;
        private double minResidual = 1e-6;
        private boolean postBpLocalBeliefPropagation = false;

        public BpConfigBuilder setMaxIter(int maxIter) {
            this.maxIter = maxIter;
            return this;
        }
        public BpConfigBuilder setBpSchedule(BpConfig.BpSchedule bpSchedule) {
            this.bpSchedule = bpSchedule;
            return this;
        }

        public BpConfigBuilder setRbpNorm(BpConfig.RbpNorm rbpNorm) {
            this.rbpNorm = rbpNorm;
            return this;
        }
        public BpConfigBuilder setMinResidual(double minResidual) {
            this.minResidual = minResidual;
            return this;
        }

        public BpConfigBuilder setPostBpLocalBeliefPropagation(boolean postBpLocalBeliefPropagation) {
            this.postBpLocalBeliefPropagation = postBpLocalBeliefPropagation;
            return this;
        }

        public BpConfig createBpConfig() {
            return new BpConfig(maxIter, bpSchedule, rbpNorm, minResidual, postBpLocalBeliefPropagation);
        }
    }

    public enum BpSchedule {
        Flood,
        RBP,
        WDBP;

        public boolean isAsync() { return this == RBP || this == WDBP; }
    }

    public enum RbpNorm {
        L1,
        L2,
        LInf
    }
}
