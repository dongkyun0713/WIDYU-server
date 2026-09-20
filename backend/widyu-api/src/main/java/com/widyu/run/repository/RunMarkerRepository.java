package com.widyu.run.repository;

import com.widyu.run.RunMarker;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RunMarkerRepository extends JpaRepository<RunMarker, Long> {

    Optional<RunMarker> findByMarkerId(String markerId);

    List<RunMarker> findByRun_IdOrderByTsMsAsc(Long runDbId);
}
