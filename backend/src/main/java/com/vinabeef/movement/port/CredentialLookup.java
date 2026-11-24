package com.vinabeef.movement.port;

import java.util.List;

/** Output port: the cards known for a credential number typed by a USB reader. */
public interface CredentialLookup {

    /** The card(s) Safetrust itself reported for this number, looking at its latest reads. */
    List<String> cardsByHistory(String credential);

    /** The active tag(s) registered with this number in the cattle register. */
    List<String> cardsByRegister(String credential);
}
