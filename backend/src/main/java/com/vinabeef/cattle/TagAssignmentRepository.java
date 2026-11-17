package com.vinabeef.cattle;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "tag_assignments")
@Getter
@Setter
class TagAssignment {
    @Id
    @Column(name = "assignment_id")
    private String assignmentId;

    @Column(name = "animal_id", nullable = false)
    private String animalId;

    @Column(name = "tag_id", nullable = false)
    private String tagId;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;
}

public interface TagAssignmentRepository extends JpaRepository<TagAssignment, String> {
    @Query("""
        SELECT ta.animalId FROM TagAssignment ta 
        JOIN com.vinabeef.cattle.RfidTag t ON ta.tagId = t.tagId 
        WHERE t.epcValue = :epc AND (ta.validTo IS NULL OR ta.validTo > CURRENT_TIMESTAMP)
    """)
    Optional<String> findActiveAnimalByTagEpc(@Param("epc") String epc);
}