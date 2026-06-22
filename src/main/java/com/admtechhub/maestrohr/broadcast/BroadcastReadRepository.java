package com.admtechhub.maestrohr.broadcast;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface BroadcastReadRepository extends JpaRepository<BroadcastRead, UUID> {

    boolean existsByBroadcastIdAndUserEmail(UUID broadcastId, String userEmail);
}
