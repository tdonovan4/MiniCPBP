package model;

import launch.SolveXCSPFZN;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import minicpbp.search.Search;
import minicpbp.search.SearchStatistics;
import minicpbp.util.Procedure;
import minicpbp.util.exception.InconsistencyException;

import java.util.Objects;
import java.util.function.Supplier;

import static minicpbp.cp.BranchingScheme.*;
import static minicpbp.cp.BranchingScheme.domWdeg;
import static minicpbp.cp.Factory.*;

public abstract class ModelFormatFrontend {
    protected final Solver minicp = makeSolver();

    // State
    protected String solutionStr;
    protected boolean extractSolutionStr = false;
    protected boolean foundSolution = false;
    protected boolean hasFailed;

    // Tracing flags
    protected boolean traceBP = false;
    protected boolean traceSearch = false;
    protected boolean traceEntropy = false;

    // Params
    protected boolean checkSolution = false;
    protected SolveXCSPFZN.TreeSearchType searchType = SolveXCSPFZN.TreeSearchType.DFS;
    protected int maxIter = 5;
    protected boolean damp = false;
    protected double dampingFactor = 0.5;
    protected boolean restart = false;
    protected int nbFailCutof = 100;
    protected double restartFactor = 1.5;
    protected boolean initImpact = false;

    // TODO: unused fields, figure out if we want to keep them
    protected double variationThreshold = -Double.MAX_VALUE;
    protected boolean dynamicStopBP = false;
    protected boolean traceNbIter = false;

    abstract public void initModel();

    abstract public String getSolutionStr();

    abstract public IntVar[] getDecisionVars();

    abstract public ModelGoal getGoal();

    abstract public IntVar getObjectiveVar();

    abstract public void onSolutionFound(SearchStatistics stats, String solFileStr);

    abstract public void onNoSolutionFound(SearchStatistics stats);

    abstract public void onInconclusiveSearch(SearchStatistics stats);

    abstract public void verifySolution();

    abstract public void printSolution(String solFileStr);

    abstract public void printStats(SearchStatistics stats, String statsFileStr, Long runtime);


    public void onPreInitFail() {
        System.out.println("problem failed before initiating the search");
        throw InconsistencyException.INCONSISTENCY;
    }

    /**
     * Creates a search (either DFS or LDS) with a given branching heuristic
     * @param branching a branching heuristic
     * @return a search object
     */
    private Search makeSearch(Supplier<Procedure[]> branching) {
        Search search = null;
        switch (searchType) {
            case DFS:
                search = makeDfs(minicp, branching);
                break;
            case LDS:
                search = makeLds(minicp, branching);
                break;
            default:
                System.out.println("unknown search type");
                System.exit(1);
        }
        return search;
    }

    public void solve(SolveXCSPFZN.BranchingHeuristic heuristic, int timeout, String statsFileStr, String solFileStr) {
        long t0 = System.currentTimeMillis();

        minicp.setTraceBPFlag(traceBP);
        minicp.setTraceSearchFlag(traceSearch);
        minicp.setTraceEntropyFlag(traceEntropy);
        minicp.setMaxIter(maxIter);
        // TODO: check if these should be commented out or removed
//		minicp.setDamp(damp);
//		minicp.setDampingFactor(dampingFactor);
//		minicp.setDynamicStopBP(dynamicStopBP);
//      minicp.setTraceNbIterFlag(traceNbIter);
//		minicp.setVariationThreshold(variationThreshold);

        if (hasFailed) {
            this.onPreInitFail();
        }

        initModel();

        IntVar[] decisionsVars = getDecisionVars();
        Search search = null;
        switch (heuristic) {
            case FFRV:
                minicp.setMode(Solver.PropaMode.SP);
                search = makeSearch(firstFailRandomVal(decisionsVars));
                break;
            case MXMS:
                search = makeSearch(maxMarginalStrength(decisionsVars));
                break;
            case MXM:
                search = makeSearch(maxMarginal(decisionsVars));
                break;
            case MNMS:
                search = makeSearch(minMarginalStrength(decisionsVars));
                break;
            case MNM:
                search = makeSearch(minMarginal(decisionsVars));
                break;
            case MNE:
                search = makeSearch(minEntropy(decisionsVars));
                break;
            case IE:
                search = makeSearch(impactEntropy(decisionsVars));
                if (initImpact)
                    search.initializeImpact(decisionsVars);
                break;
            case IBS:
                minicp.setMode(Solver.PropaMode.SP);
                search = makeSearch(impactBasedSearch(decisionsVars));
                // Optional initialisation of impacts
                search.initializeImpactDomains(decisionsVars);
                nbFailCutof = nbFailCutof * decisionsVars.length;
                break;
            case MIE:
                search = makeDfs(minicp, minEntropyRegisterImpact(decisionsVars), impactEntropy(decisionsVars));
                if (initImpact)
                    search.initializeImpact(decisionsVars);
                break;
            case MNEBW:
                search = makeSearch(minEntropyBiasedWheelSelectVal(decisionsVars));
                break;
            case WDEG:
                minicp.setMode(Solver.PropaMode.SP);
                search = makeSearch(domWdeg(decisionsVars));
                nbFailCutof = nbFailCutof * decisionsVars.length;
                break;
            default:
                System.out.println("unknown search strategy");
                System.exit(1);
        }

        if (checkSolution || (!Objects.equals(solFileStr, "")))
            extractSolutionStr = true;

        search.onSolution(() -> {
            foundSolution = true;
            solutionStr = getSolutionStr();
        });

        SearchStatistics stats;
        switch (getGoal()) {
            //find a solution that maximize the cost function
            case MAX:
                stats = search.optimize(minicp.maximize(getObjectiveVar()),
                        ss -> (System.currentTimeMillis() - t0 >= timeout * 1000L));
                break;
            //find a solution that minimize the cost function
            case MIN:
                stats = search.optimize(minicp.minimize(getObjectiveVar()),
                        ss -> (System.currentTimeMillis() - t0 >= timeout * 1000L));
                break;
            default:
                //find a solution that satisfies all constraints without restart
                if(!restart) {
                    stats = search.solve(ss -> (System.currentTimeMillis() - t0 >= timeout * 1000L || foundSolution));
                }
                //find a solution that satisfies all constraints with restarts during the search
                else {
                    stats = search.solveRestarts(ss -> (System.currentTimeMillis() - t0 >= timeout * 1000L || foundSolution), nbFailCutof, restartFactor);
                }
                break;
        }
        Long runtime = System.currentTimeMillis() - t0;

        // Print result

        if (foundSolution) {
            onSolutionFound(stats, solFileStr);
            if (checkSolution)
                verifySolution();
            printSolution(solFileStr);
        } else if (stats.isCompleted()) {
            onNoSolutionFound(stats);
        } else {
            onInconclusiveSearch(stats);
        }
        printStats(stats, statsFileStr, runtime);
    }

    public void traceBP(boolean traceBP) {
        this.traceBP = traceBP;
    }
    public void traceSearch(boolean traceSearch) {
        this.traceSearch = traceSearch;
    }
    public void traceEntropy(boolean traceEntropy) {
        this.traceEntropy = traceEntropy;
    }

    public void checkSolution(boolean checkSolution) {
        this.checkSolution = checkSolution;
    }
    public void searchType(SolveXCSPFZN.TreeSearchType searchType) {
        this.searchType = searchType;
    }
    public void maxIter(int maxIter) {
        this.maxIter = maxIter;
    }
    public void damp(boolean damp) {
        this.damp = damp;
    }
    public void dampingFactor(double dampingFactor) {
        this.dampingFactor = dampingFactor;
    }
    public void restart(boolean restart) {
        this.restart = restart;
    }
    public void nbFailCutof(int nbFailCutof) {
        this.nbFailCutof = nbFailCutof;
    }
    public void restartFactor(double restartFactor) {
        this.restartFactor = restartFactor;
    }
    public void initImpact(boolean initImpact) {
        this.initImpact = initImpact;
    }

    public void variationThreshold(double variationThreshold) {
        this.variationThreshold = variationThreshold;
    }
    public void dynamicStopBP(boolean dynamicStopBP) {
        this.dynamicStopBP = dynamicStopBP;
    }
    public void traceNbIter(boolean traceNbIter) {
        this.traceNbIter = traceNbIter;
    }
}

