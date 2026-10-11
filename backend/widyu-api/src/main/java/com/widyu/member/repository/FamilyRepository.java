package com.widyu.member.repository;

import com.widyu.member.Family;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamilyRepository extends JpaRepository<Family, Long> {

    Optional<Family> findByFamilyCode(String familyCode);

    boolean existsByFamilyCode(String familyCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Family f WHERE f.id = :familyId")
    Optional<Family> findByIdForUpdate(@Param("familyId") Long familyId);

    @Modifying
    @Query("UPDATE Family f SET f.familyOrderRevision = f.familyOrderRevision + 1 WHERE f.id = :familyId")
    int incrementOrderRevision(@Param("familyId") Long familyId);
}
