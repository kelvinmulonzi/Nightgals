package com.nightgals.live;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface LiveUsageRepository extends JpaRepository<LiveUsageDaily, UUID> {

    Optional<LiveUsageDaily> findByCreatorIdAndUsageDate(UUID creatorId, LocalDate usageDate);
}
