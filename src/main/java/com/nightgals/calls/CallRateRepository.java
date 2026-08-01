package com.nightgals.calls;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CallRateRepository extends JpaRepository<CallRate, UUID> {

    List<CallRate> findByCreatorIdAndActiveTrueOrderByDurationMinutesAsc(UUID creatorId);

    List<CallRate> findByCreatorIdOrderByDurationMinutesAsc(UUID creatorId);

    Optional<CallRate> findByCreatorIdAndDurationMinutes(UUID creatorId, int durationMinutes);
}
