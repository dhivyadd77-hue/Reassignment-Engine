package com.ziprun.reassignment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

	@Id
	private String id;

	@Column(nullable = false, length = 500)
	private String description;

	@Column(name = "assigned_agent_id")
	private String assignedAgentId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/** Sprint 2/3 placeholder */
	@Column(name = "weight_class")
	private String weightClass;

	/** Sprint 2 placeholder — zone affinity */
	@Column(name = "pickup_zone")
	private String pickupZone;

	/** Sprint 2 placeholder — zone affinity */
	@Column(name = "dropoff_zone")
	private String dropoffZone;

	public Order() {
	}

	@PrePersist
	void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getAssignedAgentId() {
		return assignedAgentId;
	}

	public void setAssignedAgentId(String assignedAgentId) {
		this.assignedAgentId = assignedAgentId;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public void setStatus(OrderStatus status) {
		this.status = status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public String getWeightClass() {
		return weightClass;
	}

	public void setWeightClass(String weightClass) {
		this.weightClass = weightClass;
	}

	public String getPickupZone() {
		return pickupZone;
	}

	public void setPickupZone(String pickupZone) {
		this.pickupZone = pickupZone;
	}

	public String getDropoffZone() {
		return dropoffZone;
	}

	public void setDropoffZone(String dropoffZone) {
		this.dropoffZone = dropoffZone;
	}
}
