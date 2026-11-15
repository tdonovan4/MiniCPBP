/*
 * mini-cpbp, replacing classic propagation by belief propagation
 */

package minicpbp.search;

/**
 * A wall-clock deadline for search limits, checked without reading the clock.
 * A daemon thread sleeps until the deadline and then sets a volatile flag, so
 * a limit predicate evaluated at every node costs one volatile read instead of
 * a clock read (about 1.3 us per call on some virtualised hosts, against a
 * dom/wdeg node of about 12 us). The limit fires within a few milliseconds of
 * the deadline, as the per-node clock test did.
 */
public final class Deadline {

    private final long atMillis;
    private volatile boolean passed;
    private volatile boolean cancelled;
    private final Thread timer;

    /** a deadline at {@code atMillis}, in {@link System#currentTimeMillis()} time */
    public Deadline(long atMillis) {
        this.atMillis = atMillis;
        if (atMillis <= System.currentTimeMillis()) {
            passed = true;
            timer = null;
            return;
        }
        timer = new Thread(this::await, "minicpbp-deadline");
        timer.setDaemon(true);
        timer.start();
    }

    private void await() {
        long left;
        while (!cancelled && (left = atMillis - System.currentTimeMillis()) > 0) {
            try {
                Thread.sleep(left);
            } catch (InterruptedException e) {
                // woken by cancel(), or spuriously: the loop condition decides
            }
        }
        if (!cancelled) passed = true;
    }

    /** true once the deadline has passed */
    public boolean passed() {
        return passed;
    }

    /** stops the timer thread; the deadline then never passes */
    public void cancel() {
        cancelled = true;
        if (timer != null) timer.interrupt();
    }
}
