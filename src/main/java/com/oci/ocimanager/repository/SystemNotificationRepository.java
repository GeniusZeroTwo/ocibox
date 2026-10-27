package com.oci.ocimanager.repository;

import com.oci.ocimanager.entity.SystemNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface SystemNotificationRepository extends JpaRepository<SystemNotification, Long> {

    long countByIsReadFalse();

    List<SystemNotification> findByIsReadFalseOrderByCreatedAtDesc(Pageable pageable);

    List<SystemNotification> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<SystemNotification> findByCategoryOrderByCreatedAtDesc(String category, Pageable pageable);

    List<SystemNotification> findByCategoryAndIsReadFalseOrderByCreatedAtDesc(String category, Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE SystemNotification n SET n.isRead = true WHERE n.isRead = false")
    int markAllAsRead();

    @Modifying
    @Transactional
    @Query("UPDATE SystemNotification n SET n.isRead = true WHERE n.id = :id")
    int markAsReadById(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("DELETE FROM SystemNotification n WHERE n.isRead = true")
    int deleteAllRead();

    @Query("SELECT n.id FROM SystemNotification n ORDER BY n.createdAt DESC")
    List<Long> findTopIds(Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM SystemNotification n WHERE n.id NOT IN :keepIds")
    int deleteOldRecordsNotIn(@Param("keepIds") List<Long> keepIds);
}
