package com.ziprun.reassignment.web;

import com.ziprun.reassignment.domain.Order;
import com.ziprun.reassignment.domain.OrderStatus;
import com.ziprun.reassignment.domain.ReassignmentSuggestion;
import com.ziprun.reassignment.domain.SuggestionStatus;
import com.ziprun.reassignment.dto.AgentMapper;
import com.ziprun.reassignment.dto.ApiDtos.AgentResponse;
import com.ziprun.reassignment.dto.ApiDtos.AgentStatusRequest;
import com.ziprun.reassignment.dto.ApiDtos.CreateOrderRequest;
import com.ziprun.reassignment.dto.ApiDtos.SuggestionDecisionRequest;
import com.ziprun.reassignment.service.ReassignmentService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ReassignmentController {

	private static final Logger log = LoggerFactory.getLogger(ReassignmentController.class);

	private final ReassignmentService reassignmentService;
	private final AgentMapper agentMapper;

	public ReassignmentController(ReassignmentService reassignmentService, AgentMapper agentMapper) {
		this.reassignmentService = reassignmentService;
		this.agentMapper = agentMapper;
	}

	@GetMapping("/agents")
	public List<AgentResponse> agents() {
		return agentMapper.toResponseList(reassignmentService.listAgents());
	}

	@GetMapping("/orders")
	public List<Order> orders(@RequestParam(required = false) OrderStatus status) {
		return reassignmentService.listOrders(status);
	}

	@PostMapping("/orders")
	public ResponseEntity<Order> createOrder(@RequestBody CreateOrderRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("request body is required");
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(reassignmentService.createOrder(request));
	}

	@PatchMapping("/agents/{id}/status")
	public AgentResponse updateAgentStatus(@PathVariable String id, @RequestBody AgentStatusRequest request) {
		if (request == null || request.status() == null) {
			throw new IllegalArgumentException("status is required");
		}
		log.info("PATCH /agents/{}/status -> {}", id, request.status());
		return agentMapper.toResponse(reassignmentService.updateAgentStatus(id, request.status()));
	}

	@PostMapping("/orders/{id}/suggest")
	public ResponseEntity<ReassignmentSuggestion> suggest(@PathVariable String id) {
		return ResponseEntity.status(HttpStatus.CREATED).body(reassignmentService.suggest(id));
	}

	@GetMapping("/suggestions")
	public List<ReassignmentSuggestion> suggestions(@RequestParam(required = false) SuggestionStatus status) {
		return reassignmentService.listSuggestions(status);
	}

	@PatchMapping("/suggestions/{id}")
	public ReassignmentSuggestion decide(
			@PathVariable Long id,
			@RequestBody SuggestionDecisionRequest request) {
		if (request == null || request.status() == null) {
			throw new IllegalArgumentException("status is required");
		}
		return reassignmentService.decide(id, request.status());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
		return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
	}
}
