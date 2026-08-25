package com.ziprun.reassignment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
		name = "reassignment_suggestions",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_suggestion_order_status_trigger",
				columnNames = {"order_id", "status", "trigger_reason"}))
public class ReassignmentSuggestion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "order_id", nullable = false)
	private String orderId;

	/**
	 * Nullable when no eligible agent exists (zero-agent fallback warning suggestion).
	 * Accept path must reject null recommended agents.
	 */
	@Column(name = "recommended_agent_id")
	private String recommendedAgentId;

	@Column(nullable = false)
	private double confidence;

	@Column(nullable = false, length = 2000)
	private String reasoning;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SuggestionStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_reason", nullable = false)
	private TriggerReason triggerReason;

	@Column(name = "strategy_used")
	private String strategyUsed;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public ReassignmentSuggestion() {
	}

	@PrePersist
	void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
		if (status == null) {
			status = SuggestionStatus.PENDING;
		}
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getOrderId() {
		return orderId;
	}

	public void setOrderId(String orderId) {
		this.orderId = orderId;
	}

	public String getRecommendedAgentId() {
		return recommendedAgentId;
	}

	public void setRecommendedAgentId(String recommendedAgentId) {
		this.recommendedAgentId = recommendedAgentId;
	}

	public double getConfidence() {
		return confidence;
	}

	public void setConfidence(double confidence) {
		this.confidence = confidence;
	}

	public String getReasoning() {
		return reasoning;
	}

	public void setReasoning(String reasoning) {
		this.reasoning = reasoning;
	}

	public SuggestionStatus getStatus() {
		return status;
	}

	public void setStatus(SuggestionStatus status) {
		this.status = status;
	}

	public TriggerReason getTriggerReason() {
		return triggerReason;
	}

	public void setTriggerReason(TriggerReason triggerReason) {
		this.triggerReason = triggerReason;
	}

	public String getStrategyUsed() {
		return strategyUsed;
	}

	public void setStrategyUsed(String strategyUsed) {
		this.strategyUsed = strategyUsed;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
