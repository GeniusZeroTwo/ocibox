package com.oci.ocimanager.repository;

import com.oci.ocimanager.entity.AdminPasskey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdminPasskeyRepository extends JpaRepository<AdminPasskey, Long> {
    Optional<AdminPasskey> findByCredentialId(String credentialId);
    List<AdminPasskey> findAllByOrderByCreatedAtDesc();
}
