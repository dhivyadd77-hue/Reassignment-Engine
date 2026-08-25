package com.ziprun.reassignment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "agents")
public class Agent {

	/** When maxCapacity is unset, treat capacity as 1 (AVAILABLE → BUSY on first order). */
	public static final int DEFAULT_MAX_CAPACITY = 1;

	@Id
	private String id;

	@Column(nullable = false)
	private String name;

	@Column(name = "active_order_count", nullable = false)
	private int activeOrderCount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AgentStatus status;

	/** Sprint 2 placeholder — zone affinity */
	@Column(name = "current_zone")
	private String currentZone;

	/** Sprint 2 — capacity; null uses {@link #DEFAULT_MAX_CAPACITY} for auto rules */
	@Column(name = "max_capacity")
	private Integer maxCapacity;

	/**
	 * When true, automatic load rules must not change status while OFFLINE.
	 * Cleared when load drains to 0 (after accepts) or when the user chooses Make AVAILABLE.
	 */
	@Column(name = "manual_override", nullable = false)
	private boolean manualOverride = false;

	public Agent() {
	}

	public Agent(String id, String name, int activeOrderCount, AgentStatus status) {
		this.id = id;
		this.name = name;
		this.activeOrderCount = activeOrderCount;
		this.status = status;
	}

	public int effectiveMaxCapacity() {
		return maxCapacity == null || maxCapacity < 1 ? DEFAULT_MAX_CAPACITY : maxCapacity;
	}

	public boolean isAtOrOverCapacity() {
		return activeOrderCount >= effectiveMaxCapacity();
	}

	public boolean isUnderCapacity() {
		return activeOrderCount < effectiveMaxCapacity();
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getActiveOrderCount() {
		return activeOrderCount;
	}

	public void setActiveOrderCount(int activeOrderCount) {
		this.activeOrderCount = activeOrderCount;
	}

	public AgentStatus getStatus() {
		return status;
	}

	public void setStatus(AgentStatus status) {
		this.status = status;
	}

	public String getCurrentZone() {
		return currentZone;
	}

	public void setCurrentZone(String currentZone) {
		this.currentZone = currentZone;
	}

	public Integer getMaxCapacity() {
		return maxCapacity;
	}

	public void setMaxCapacity(Integer maxCapacity) {
		this.maxCapacity = maxCapacity;
	}

	public boolean isManualOverride() {
		return manualOverride;
	}

	public void setManualOverride(boolean manualOverride) {
		this.manualOverride = manualOverride;
	}
}
