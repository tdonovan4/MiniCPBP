package minicpbp.util;

/**
 * Search-side state that BP spending rules read (RESEARCH_PLAN.md, "Search-specific BP spending rules").
 * Written by the search, read by MiniCP's reuse decision; a plain holder so that the engine does not
 * depend on the search package. Single-threaded, like the solver.
 */
public final class SearchContext {
    private SearchContext() { }

    /** discrepancies the current LDS path may still take in this pass; Integer.MAX_VALUE outside LDS */
    public static int ldsBudgetLeft = Integer.MAX_VALUE;
}
