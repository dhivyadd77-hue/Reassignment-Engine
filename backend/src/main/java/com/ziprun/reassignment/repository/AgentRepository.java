package com.ziprun.reassignment.repository;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, String> {

	List<Agent> findByStatus(AgentStatus status);

	/** Eligible for routing: anyone not OFFLINE (AVAILABLE + BUSY). */
	List<Agent> findByStatusNot(AgentStatus status);

	/** Pessimistic write lock for hybrid auto/manual load transitions. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Agent a where a.id = :id")
	Optional<Agent> findByIdForUpdate(@Param("id") String id);
}
