package com.vinabeef.movement.port;

import java.util.Optional;

/** Output port: what the movement use case needs to know about animals and their tags. */
public interface AnimalRegistry {

    /** The animal that wears this tag now. Empty when the tag has no active assignment: no animal is ever invented. */
    Optional<String> activeAnimalForTag(String tagEpc);

    /** True when an animal with this id exists and is not archived. */
    boolean isLiveAnimal(String animalId);

    /** The barn the animal is assigned to, when it has one. */
    Optional<String> assignedBarn(String animalId);
}
