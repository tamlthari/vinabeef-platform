package com.vinabeef.movement.adapter;

import com.vinabeef.cattle.TagAssignmentRepository;
import com.vinabeef.movement.port.AnimalRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@link AnimalRegistry}: tag assignments through their repository, the animals table through JDBC. */
@Component
@RequiredArgsConstructor
public class JdbcAnimalRegistry implements AnimalRegistry {

    private final TagAssignmentRepository tagAssignments;
    private final JdbcTemplate jdbc;

    @Override
    public Optional<String> activeAnimalForTag(String tagEpc) {
        return tagAssignments.findActiveAnimalByTagEpc(tagEpc);
    }

    @Override
    public boolean isLiveAnimal(String animalId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM animals WHERE animal_id = ? AND lifecycle_status <> 'ARCHIVED'", Integer.class, animalId);
        return count != null && count > 0;
    }

    @Override
    public Optional<String> assignedBarn(String animalId) {
        List<String> assignedBarns = jdbc.queryForList(
                "SELECT assigned_barn_id FROM animals WHERE animal_id = ?", String.class, animalId);
        return assignedBarns.isEmpty() ? Optional.empty() : Optional.ofNullable(assignedBarns.get(0));
    }
}
