package com.admtechhub.maestrohr.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface InAppNotificationRepository extends JpaRepository<InAppNotification, UUID> {
    List<InAppNotification> findTop20ByRecipientEmailOrderByCreatedAtDesc(String recipientEmail);

    long countByRecipientEmailAndIsReadFalse(String recipientEmail);

    @Modifying
    @Transactional
    @Query("UPDATE InAppNotification n SET n.isRead = true WHERE n.id = :id AND n.recipientEmail = :recipientEmail")
    int markAsRead(@Param("id") UUID id, @Param("recipientEmail") String recipientEmail);

    @Modifying
    @Transactional
    @Query("UPDATE InAppNotification n SET n.isRead = true WHERE n.recipientEmail = :recipientEmail AND n.isRead = false")
    int markAllAsRead(@Param("recipientEmail") String recipientEmail);
}
