package com.vinabeef.movement.port;

/** Output port: the counters kept on a barn. */
public interface BarnCounters {

    /** Moves the barn's headcount by {@code delta}. */
    void adjustHeadcount(String barnId, int delta);

    /** One more animal came in today. */
    void countIn(String barnId);

    /** One more animal went out today. */
    void countOut(String barnId);
}
