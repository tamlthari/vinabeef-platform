package com.vinabeef.ingestion.port;

import java.util.List;

/** Output port: where events that could not be processed are kept until somebody replays them. */
public interface DeadLetterStore {

    /** A kept event that has not been replayed yet. */
    record OpenLetter(long id, String payload) {
    }

    void add(String ingressMode, Long eventKey, String error, String payload);

    int countOpen();

    /** At most {@code limit} open letters, oldest first. */
    List<OpenLetter> findOpen(int limit);

    void markReplayed(long id);

    void markFailed(long id, String error);
}
