package com.vinabeef.facilities;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BarnRepository extends JpaRepository<Barn, String> {
    @Modifying
    @Query("UPDATE Barn b SET b.currentHeadcount = b.currentHeadcount + :delta WHERE b.barnId = :barnId")
    void adjustHeadcount(@Param("barnId") String barnId, @Param("delta") int delta);
}