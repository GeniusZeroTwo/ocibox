package com.oci.ocimanager.repository;

import com.oci.ocimanager.entity.CreationTaskRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreationTaskRepository extends JpaRepository<CreationTaskRecord, String> {
    List<CreationTaskRecord> findByStatus(String status);
}
