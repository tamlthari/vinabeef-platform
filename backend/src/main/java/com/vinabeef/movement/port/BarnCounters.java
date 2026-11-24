package com.vinabeef.movement.port;

/** Output port: the counters kept on a barn. */
public interface BarnCounters {

    /** Moves the barn's headcount by {@code delta}. */
    void adjustHeadcount(String barnId, int delta);

    /** One more animal came in today. */
    void countIn(String barnId);

    /** One more animal went out today. */
    void countOut(String barnId);

    /** Takes back one {@link #countIn}: a half crossing counted as IN turned out to be OUT. Never below zero. */
    void uncountIn(String barnId);

    /** Takes back one {@link #countOut}. Never below zero. */
    void uncountOut(String barnId);
}
