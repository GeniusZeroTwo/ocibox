package com.oci.ocimanager.repository;

import com.oci.ocimanager.entity.TrafficLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrafficLimitRepository extends JpaRepository<TrafficLimit, String> {}
