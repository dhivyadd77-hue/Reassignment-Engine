package com.ziprun.reassignment.config;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.Order;
import com.ziprun.reassignment.domain.OrderStatus;
import com.ziprun.reassignment.repository.AgentRepository;
import com.ziprun.reassignment.repository.OrderRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Safety-net seed when {@code data.sql} did not populate the roster
 * (e.g. empty DB after a partial init). Idempotent: only runs when agent count is 0.
 */
@Component
@org.springframework.core.annotation.Order(1)
public class SeedDataRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

	private final AgentRepository agentRepository;
	private final OrderRepository orderRepository;

	public SeedDataRunner(AgentRepository agentRepository, OrderRepository orderRepository) {
		this.agentRepository = agentRepository;
		this.orderRepository = orderRepository;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (agentRepository.count() > 0) {
			log.info("Seed skipped — {} agent(s) already present (data.sql or prior run)", agentRepository.count());
			return;
		}

		log.warn("No agents found — inserting Sprint-1 demo seed via SeedDataRunner");

		agentRepository.save(agent("AGT-001", "Priya Sharma", 3, AgentStatus.BUSY));
		agentRepository.save(agent("AGT-002", "Rahul Verma", 0, AgentStatus.AVAILABLE));
		agentRepository.save(agent("AGT-003", "Ananya Iyer", 2, AgentStatus.BUSY));
		agentRepository.save(agent("AGT-004", "Kiran Nair", 0, AgentStatus.AVAILABLE));
		agentRepository.save(agent("AGT-005", "Deepak Mehta", 3, AgentStatus.BUSY));

		orderRepository.save(delivery("ORD-001", "Electronics — Koramangala to Indiranagar", "AGT-001"));
		orderRepository.save(delivery("ORD-002", "Groceries — HSR Layout to BTM", "AGT-001"));
		orderRepository.save(delivery("ORD-003", "Pharma — Whitefield to Marathahalli", "AGT-003"));
		orderRepository.save(delivery("ORD-004", "Documents — MG Road to Jayanagar", "AGT-005"));
		orderRepository.save(delivery("ORD-005", "Food — Bellandur to Electronic City", "AGT-005"));
		orderRepository.save(delivery("ORD-006", "Apparel — Malleshwaram to Rajajinagar", "AGT-005"));
		orderRepository.save(delivery("ORD-007", "Books — Banashankari to JP Nagar", "AGT-003"));
		orderRepository.save(delivery("ORD-008", "Hardware — Peenya to Yeshwanthpur", "AGT-001"));

		log.info("Seed complete — 5 agents, 8 orders");
	}

	private static Agent agent(String id, String name, int load, AgentStatus status) {
		Agent a = new Agent(id, name, load, status);
		a.setCurrentZone(null);
		a.setMaxCapacity(3);
		a.setManualOverride(false);
		return a;
	}

	private static Order delivery(String id, String description, String agentId) {
		Order o = new Order();
		o.setId(id);
		o.setDescription(description);
		o.setAssignedAgentId(agentId);
		o.setStatus(OrderStatus.ASSIGNED);
		o.setWeightClass(null);
		o.setPickupZone(null);
		o.setDropoffZone(null);
		return o;
	}
}
