package com.nightgals.kyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KycAccessLogRepository extends JpaRepository<KycAccessLog, UUID> {
}
