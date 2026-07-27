package minicpbp.util;

import java.util.Arrays;
import java.util.NoSuchElementException;

public class IndexedMaxPQ<Key extends Comparable<Key>> {
    // Max number of elements
    private int capacity;
    // Current number of elements in pq
    private int n;
    // Binary heap
    private int[] pq;
    // Inverse of pq
    private int[] qp;
    // Stores priority values
    private Key[] keys;

    @SuppressWarnings("unchecked")
    public IndexedMaxPQ(int initialCapacity) {
        this.capacity = initialCapacity;
        this.n = 0;
        this.keys = (Key[]) new Comparable[capacity + 1];
        this.pq = new int[capacity + 1];
        this.qp = new int[capacity + 1];
        Arrays.fill(qp, -1);
    }

    public boolean contains(int i) {
        return qp[i] != -1;
    }

    /*
     * Insert a key and return the given index
     */
    public int insert(Key key) {
        if (n == capacity) {
            resize(capacity * 2);
        }
        for (int i = 0; i < capacity; i++) {
            if (qp[i] == -1) {
                insert(i, key);
                return i;
            }
        }
        throw new RuntimeException("could not find empty slot to insert key");
    }

    /*
     * Insert a key at the index
     */
    public void insert(int i, Key key) {
        if (contains(i)) throw new IllegalArgumentException("index already in PQ");
        if (i >= capacity) {
            resize(Math.max(i + 1, capacity * 2));
        }
        n++;
        qp[i] = n;
        pq[n] = i;
        keys[i] = key;
        swim(n);
    }

    /*
     * Get the key at the index
     */
    public Key get(int i) {
        if (contains(i)) {
            return keys[i];
        }
        return null;
    }

    /*
     * Update the key associated with index i to a new value
     */
    public void update(int i, Key key) {
        if (!contains(i)) throw new NoSuchElementException("index not in PQ");
        keys[i] = key;
        swim(qp[i]);
        sink(qp[i]);
    }

    public void clear() {
        Arrays.fill(qp, -1);
        Arrays.fill(keys, null);
        n = 0;
    }

    /*
     * Returns top index
     */
    public int topIndex() {
        return pq[1];
    }

    /*
     * Returns top key
     */
    public Key topKey() {
        return keys[pq[1]];
    }

    private boolean less(int i, int j) {
        return keys[pq[i]].compareTo(keys[pq[j]]) < 0;
    }

    private void swap(int i, int j) {
        int swap = pq[i];
        pq[i] = pq[j];
        pq[j] = swap;
        qp[pq[i]] = i;
        qp[pq[j]] = j;
    }

    private void swim(int k) {
        while (k > 1 && less(k / 2, k)) {
            swap(k, k / 2);
            k = k / 2;
        }
    }

    private void sink(int k) {
        while (2 * k <= n) {
            int j = 2 * k;
            if (j < n && less(j, j + 1)) {
                j++;
            }
            if (!less(k, j)) {
                break;
            }
            swap(k, j);
            k = j;
        }
    }

    /**
     * Dynamically resizes the underlying arrays when capacity limits are hit.
     */
    @SuppressWarnings("unchecked")
    private void resize(int capacity) {
        Key[] tempKeys = (Key[]) new Comparable[capacity + 1];
        int[] tempPq = new int[capacity + 1];
        int[] tempQp = new int[capacity + 1];
        Arrays.fill(tempQp, -1);

        System.arraycopy(keys, 0, tempKeys, 0, Math.min(keys.length, tempKeys.length));
        System.arraycopy(pq, 0, tempPq, 0, Math.min(pq.length, tempPq.length));
        System.arraycopy(qp, 0, tempQp, 0, Math.min(qp.length, tempQp.length));

        this.keys = tempKeys;
        this.pq = tempPq;
        this.qp = tempQp;
        this.capacity = capacity;
    }
}