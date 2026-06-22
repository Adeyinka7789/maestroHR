package com.admtechhub.maestrohr.broadcast;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BroadcastRepository extends JpaRepository<Broadcast, UUID> {

    List<Broadcast> findAllByOrderByCreatedAtDesc();

    /**
     * Broadcasts the given user has not yet read, newest first. Plan-tier targeting is applied
     * in {@link BroadcastService} (the comma-separated {@code target_plans} string is awkward to
     * match in JPQL); this query only enforces the unread predicate.
     */
    @Query("SELECT b FROM Broadcast b WHERE b.id NOT IN "
            + "(SELECT r.broadcastId FROM BroadcastRead r WHERE r.userEmail = :email) "
            + "ORDER BY b.createdAt DESC")
    List<Broadcast> findUnreadFor(@Param("email") String email);

    /** Read counts per broadcast, for the superadmin "sent" table. */
    @Query("SELECT r.broadcastId, COUNT(r) FROM BroadcastRead r GROUP BY r.broadcastId")
    List<Object[]> countReadsByBroadcast();
}
