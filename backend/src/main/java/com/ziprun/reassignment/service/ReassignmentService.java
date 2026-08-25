package com.ziprun.reassignment.service;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.Order;
import com.ziprun.reassignment.domain.OrderStatus;
import com.ziprun.reassignment.domain.ReassignmentSuggestion;
import com.ziprun.reassignment.domain.SuggestionStatus;
import com.ziprun.reassignment.domain.TriggerReason;
import com.ziprun.reassignment.dto.ApiDtos.CreateOrderRequest;
import com.ziprun.reassignment.repository.AgentRepository;
import com.ziprun.reassignment.repository.OrderRepository;
import com.ziprun.reassignment.repository.ReassignmentSuggestionRepository;
import com.ziprun.reassignment.routing.Recommendation;
import com.ziprun.reassignment.routing.RoutingContext;
import com.ziprun.reassignment.routing.RoutingEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

@Service
public class ReassignmentService {

	private static final Logger log = LoggerFactory.getLogger(ReassignmentService.class);
	public static final String STRATEGY_NO_ELIGIBLE_AGENT = "no-eligible-agent";

	private final AgentRepository agentRepository;
	private final OrderRepository orderRepository;
	private final ReassignmentSuggestionRepository suggestionRepository;
	private final RoutingEngine routingEngine;
	private final AgentService agentService;
	private final TransactionTemplate transactionTemplate;

	public ReassignmentService(
			AgentRepository agentRepository,
			OrderRepository orderRepository,
			ReassignmentSuggestionRepository suggestionRepository,
			RoutingEngine routingEngine,
			AgentService agentService,
			TransactionTemplate transactionTemplate) {
		this.agentRepository = agentRepository;
		this.orderRepository = orderRepository;
		this.suggestionRepository = suggestionRepository;
		this.routingEngine = routingEngine;
		this.agentService = agentService;
		this.transactionTemplate = transactionTemplate;
	}

	@Transactional(readOnly = true)
	public List<Agent> listAgents() {
		return agentRepository.findAll();
	}

	@Transactional(readOnly = true)
	public List<Order> listOrders(OrderStatus status) {
		if (status == null) {
			return orderRepository.findAll();
		}
		return orderRepository.findByStatus(status);
	}

	@Transactional(readOnly = true)
	public List<ReassignmentSuggestion> listSuggestions(SuggestionStatus status) {
		if (status == null) {
			return suggestionRepository.findAll();
		}
		return suggestionRepository.findByStatus(status);
	}

	@Transactional
	public Order createOrder(CreateOrderRequest request) {
		if (request.id() == null || request.id().isBlank()) {
			throw new IllegalArgumentException("order id is required");
		}
		if (request.assignedAgentId() == null || request.assignedAgentId().isBlank()) {
			throw new IllegalArgumentException("assignedAgentId is required");
		}
		Agent agent = agentRepository.findById(request.assignedAgentId())
				.orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + request.assignedAgentId()));
		if (agent.getStatus() == AgentStatus.OFFLINE) {
			throw new IllegalStateException("Cannot assign order to OFFLINE agent: " + agent.getId());
		}

		Order order = new Order();
		order.setId(request.id());
		order.setDescription(request.description());
		order.setAssignedAgentId(agent.getId());
		order.setStatus(OrderStatus.ASSIGNED);
		order.setWeightClass(request.weightClass());
		order.setPickupZone(request.pickupZone());
		order.setDropoffZone(request.dropoffZone());
		Order saved = orderRepository.save(order);

		agentService.onOrderAssigned(agent.getId());
		return saved;
	}

	@Transactional
	public Agent updateAgentStatus(String agentId, AgentStatus status) {
		return agentService.applyManualStatus(agentId, status);
	}

	@Transactional
	public ReassignmentSuggestion suggest(String orderId) {
		Order order = orderRepository.findById(orderId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown order: " + orderId));

		if (suggestionRepository.existsByOrderIdAndStatus(orderId, SuggestionStatus.PENDING)) {
			throw new IllegalStateException("PENDING suggestion already exists for order " + orderId);
		}

		List<Agent> eligible = eligibleAgents(null);
		if (eligible.isEmpty()) {
			throw new IllegalStateException("No eligible agents available for suggestion");
		}
		Recommendation rec = routingEngine.recommend(order, eligible, RoutingContext.initial());
		try {
			return persistSuggestion(order, rec, TriggerReason.INITIAL);
		} catch (DataIntegrityViolationException dup) {
			throw new IllegalStateException("PENDING suggestion already exists for order " + orderId, dup);
		}
	}

	/**
	 * Re-plan only orders actively assigned to {@code failedAgentId}
	 * ({@code ASSIGNED} / {@code IN_TRANSIT}). Skips any order that already has a PENDING suggestion.
	 */
	public void replanForOfflineAgent(String failedAgentId) {
		List<Order> stranded = transactionTemplate.execute(status ->
				orderRepository.findActiveOrdersForAgent(failedAgentId).stream()
						.filter(o -> Objects.equals(failedAgentId, o.getAssignedAgentId()))
						.toList());
		if (stranded == null) {
			stranded = List.of();
		}

		List<Agent> eligible = transactionTemplate.execute(status -> eligibleAgents(failedAgentId));
		if (eligible == null) {
			eligible = List.of();
		}

		log.info("Re-planning {} order(s) strictly for offline agent {} ({} eligible candidate(s))",
				stranded.size(), failedAgentId, eligible.size());

		RoutingContext ctx = RoutingContext.offline(failedAgentId, stranded.size());
		for (Order orderSnapshot : stranded) {
			if (!Objects.equals(failedAgentId, orderSnapshot.getAssignedAgentId())) {
				log.warn("Skipping order {} — assignedAgentId={} does not match offline agent {}",
						orderSnapshot.getId(), orderSnapshot.getAssignedAgentId(), failedAgentId);
				continue;
			}

			Boolean alreadyPending = transactionTemplate.execute(status ->
					suggestionRepository.existsByOrderIdAndStatus(
							orderSnapshot.getId(), SuggestionStatus.PENDING));
			if (Boolean.TRUE.equals(alreadyPending)) {
				log.info("Skipping order {} — PENDING suggestion already exists", orderSnapshot.getId());
				continue;
			}

			if (eligible.isEmpty()) {
				log.error("No eligible agents while re-planning order {} — persisting fallback warning",
						orderSnapshot.getId());
				persistNoAgentWarningInTx(orderSnapshot.getId(), failedAgentId);
				continue;
			}

			Recommendation rec;
			try {
				rec = routingEngine.recommend(orderSnapshot, eligible, ctx);
			} catch (Exception ex) {
				log.error("Routing failed for order {} — skipping this cycle", orderSnapshot.getId(), ex);
				continue;
			}

			persistOfflineSuggestionInTx(orderSnapshot.getId(), failedAgentId, rec);
		}
	}

	@Transactional
	public ReassignmentSuggestion decide(Long suggestionId, SuggestionStatus decision) {
		if (decision != SuggestionStatus.ACCEPTED && decision != SuggestionStatus.REJECTED) {
			throw new IllegalArgumentException("status must be ACCEPTED or REJECTED");
		}

		ReassignmentSuggestion suggestion = suggestionRepository.findById(suggestionId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown suggestion: " + suggestionId));

		if (suggestion.getStatus() != SuggestionStatus.PENDING) {
			throw new IllegalArgumentException("Suggestion already decided: " + suggestion.getStatus());
		}

		Agent newAgent = null;
		Order order = null;
		if (decision == SuggestionStatus.ACCEPTED) {
			if (suggestion.getRecommendedAgentId() == null || suggestion.getRecommendedAgentId().isBlank()) {
				throw new IllegalStateException(
						"Cannot accept suggestion without a recommended agent (zero-agent fallback)");
			}
			order = orderRepository.findById(suggestion.getOrderId())
					.orElseThrow(() -> new IllegalArgumentException("Unknown order: " + suggestion.getOrderId()));
			newAgent = agentRepository.findById(suggestion.getRecommendedAgentId())
					.orElseThrow(() -> new IllegalArgumentException(
							"Unknown agent: " + suggestion.getRecommendedAgentId()));
			if (newAgent.getStatus() == AgentStatus.OFFLINE) {
				throw new IllegalStateException(
						"Recommended agent is OFFLINE and cannot accept reassignment: " + newAgent.getId());
			}
		}

		int claimed = suggestionRepository.claimPending(suggestionId, decision, SuggestionStatus.PENDING);
		if (claimed != 1) {
			throw new IllegalArgumentException("Suggestion already decided");
		}
		suggestion.setStatus(decision);

		if (decision == SuggestionStatus.REJECTED) {
			return suggestionRepository.findById(suggestionId).orElse(suggestion);
		}

		String previousAgentId = order.getAssignedAgentId();

		order.setAssignedAgentId(newAgent.getId());
		order.setStatus(OrderStatus.REASSIGNED);
		orderRepository.save(order);

		// Drain stranded agent's load from remaining owned orders; lock clears at 0 while OFFLINE.
		if (previousAgentId != null && !previousAgentId.equals(newAgent.getId())) {
			agentService.syncLoadFromOwnedOrders(previousAgentId);
		}

		agentService.onOrderAssigned(newAgent.getId());

		return suggestionRepository.findById(suggestionId).orElse(suggestion);
	}

	private List<Agent> eligibleAgents(String excludeAgentId) {
		return agentRepository.findByStatusNot(AgentStatus.OFFLINE).stream()
				.filter(a -> excludeAgentId == null || !excludeAgentId.equals(a.getId()))
				.toList();
	}

	private void persistOfflineSuggestionInTx(String orderId, String failedAgentId, Recommendation rec) {
		try {
			transactionTemplate.executeWithoutResult(status -> {
				if (suggestionRepository.existsByOrderIdAndStatus(orderId, SuggestionStatus.PENDING)) {
					log.info("Skipping order {} — PENDING suggestion already exists", orderId);
					return;
				}
				Order order = orderRepository.findById(orderId)
						.orElseThrow(() -> new IllegalArgumentException("Unknown order: " + orderId));
				if (!Objects.equals(failedAgentId, order.getAssignedAgentId())) {
					log.warn("Abort persist for {} — assigned to {} not offline agent {}",
							orderId, order.getAssignedAgentId(), failedAgentId);
					return;
				}
				if (order.getStatus() != OrderStatus.ASSIGNED && order.getStatus() != OrderStatus.IN_TRANSIT) {
					log.warn("Abort persist for {} — status {} not ASSIGNED/IN_TRANSIT", orderId, order.getStatus());
					return;
				}
				persistSuggestion(order, rec, TriggerReason.AGENT_OFFLINE);
			});
		} catch (DataIntegrityViolationException dup) {
			log.info("Concurrent duplicate suggestion suppressed for order {}", orderId);
		}
	}

	private void persistNoAgentWarningInTx(String orderId, String failedAgentId) {
		try {
			transactionTemplate.executeWithoutResult(status -> {
				if (suggestionRepository.existsByOrderIdAndStatus(orderId, SuggestionStatus.PENDING)) {
					return;
				}
				Order order = orderRepository.findById(orderId)
						.orElseThrow(() -> new IllegalArgumentException("Unknown order: " + orderId));
				if (!Objects.equals(failedAgentId, order.getAssignedAgentId())) {
					return;
				}

				ReassignmentSuggestion suggestion = new ReassignmentSuggestion();
				suggestion.setOrderId(order.getId());
				suggestion.setRecommendedAgentId(null);
				suggestion.setConfidence(0.0);
				suggestion.setReasoning(String.format(
						"[FALLBACK WARNING] No eligible agents remain after %s went OFFLINE. Manual dispatch required for order %s.",
						failedAgentId,
						order.getId()));
				suggestion.setStatus(SuggestionStatus.PENDING);
				suggestion.setTriggerReason(TriggerReason.AGENT_OFFLINE);
				suggestion.setStrategyUsed(STRATEGY_NO_ELIGIBLE_AGENT);

				order.setStatus(OrderStatus.REASSIGNMENT_PENDING);
				orderRepository.save(order);
				suggestionRepository.save(suggestion);
			});
		} catch (DataIntegrityViolationException dup) {
			log.info("Concurrent no-agent warning already exists for order {}", orderId);
		}
	}

	private ReassignmentSuggestion persistSuggestion(Order order, Recommendation rec, TriggerReason trigger) {
		ReassignmentSuggestion suggestion = new ReassignmentSuggestion();
		suggestion.setOrderId(order.getId());
		suggestion.setRecommendedAgentId(rec.agentId());
		suggestion.setConfidence(rec.confidence());
		suggestion.setReasoning(rec.reasoning());
		suggestion.setStatus(SuggestionStatus.PENDING);
		suggestion.setTriggerReason(trigger);
		suggestion.setStrategyUsed(rec.strategyUsed());

		order.setStatus(OrderStatus.REASSIGNMENT_PENDING);
		orderRepository.save(order);
		return suggestionRepository.save(suggestion);
	}
}
