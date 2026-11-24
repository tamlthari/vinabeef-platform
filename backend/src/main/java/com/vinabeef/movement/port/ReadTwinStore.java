package com.vinabeef.movement.port;

import java.util.List;

/** Output port: finding and marking the two copies of one tap that arrived by two routes. */
public interface ReadTwinStore {

    /** Serialises the matching of one tag at one reader until the transaction ends. */
    void lock(String card, String readerName);

    /** Event ids of earlier copies of this tap (same tag, same reader, other route, within the window). */
    List<String> findTwin(String eventId, String readerName, String card, String readSource, long hardwareMs, long windowMs);

    /** Marks {@code first} as the copy that counts; false when it was paired with another copy a moment ago. */
    boolean markPrimary(String first, String eventId);

    /** Marks this read as the second copy of {@code first}. */
    void markDuplicate(String eventId, String first);
}
