package com.ziprun.reassignment.repository;

import com.ziprun.reassignment.domain.ReassignmentSuggestion;
import com.ziprun.reassignment.domain.SuggestionStatus;
import com.ziprun.reassignment.domain.TriggerReason;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReassignmentSuggestionRepository extends JpaRepository<ReassignmentSuggestion, Long> {

	List<ReassignmentSuggestion> findByStatus(SuggestionStatus status);

	boolean existsByOrderIdAndStatusAndTriggerReason(
			String orderId,
			SuggestionStatus status,
			TriggerReason triggerReason);

	/** Idempotency: any PENDING suggestion for the order blocks a new card. */
	boolean existsByOrderIdAndStatus(String orderId, SuggestionStatus status);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE ReassignmentSuggestion s
			   SET s.status = :to
			 WHERE s.id = :id
			   AND s.status = :pending
			""")
	int claimPending(
			@Param("id") Long id,
			@Param("to") SuggestionStatus to,
			@Param("pending") SuggestionStatus pending);
}
