package com.oci.ocimanager.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Entity
@Table(name = "system_notifications", indexes = {
    @Index(name = "idx_notice_is_read", columnList = "isRead"),
    @Index(name = "idx_notice_created_at", columnList = "createdAt")
})
public class SystemNotification {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false, length = 20)
    private String level; // INFO, SUCCESS, WARNING, DANGER

    @Column(nullable = false, length = 30)
    private String category; // TRAFFIC, INSTANCE, SNIPER, SYSTEM, SECURITY

    @Column(nullable = false)
    private boolean isRead = false;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public SystemNotification() {}

    public SystemNotification(String title, String content, String level, String category) {
        this.title = title != null && !title.isBlank() ? title.trim() : "系统消息";
        this.content = content != null ? content.trim() : "";
        this.level = (level != null && !level.isBlank()) ? level.trim().toUpperCase() : "INFO";
        this.category = (category != null && !category.isBlank()) ? category.trim().toUpperCase() : "SYSTEM";
        this.isRead = false;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        isRead = read;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getFormattedCreatedAt() {
        return createdAt != null ? createdAt.format(FORMATTER) : "";
    }
}
