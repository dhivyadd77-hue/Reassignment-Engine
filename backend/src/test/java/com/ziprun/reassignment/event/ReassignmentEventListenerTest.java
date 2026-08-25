package com.ziprun.reassignment.event;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.Order;
import com.ziprun.reassignment.domain.OrderStatus;
import com.ziprun.reassignment.domain.ReassignmentSuggestion;
import com.ziprun.reassignment.domain.SuggestionStatus;
import com.ziprun.reassignment.domain.TriggerReason;
import com.ziprun.reassignment.repository.AgentRepository;
import com.ziprun.reassignment.repository.OrderRepository;
import com.ziprun.reassignment.repository.ReassignmentSuggestionRepository;
import com.ziprun.reassignment.routing.Recommendation;
import com.ziprun.reassignment.routing.RoutingContext;
import com.ziprun.reassignment.routing.RoutingEngine;
import com.ziprun.reassignment.service.AgentService;
import com.ziprun.reassignment.service.ReassignmentService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Agentic re-planning loop & event listener")
class ReassignmentEventListenerTest {

	@Nested
	@ExtendWith(MockitoExtension.class)
	@DisplayName("ReassignmentEventListener")
	class ListenerTests {

		@Mock
		private ReassignmentService reassignmentService;

		@Test
		@DisplayName("Positive: catches AgentWentOfflineEvent and triggers replanForOfflineAgent")
		void listenerInvokesReplanOnEvent() {
			ReassignmentEventListener listener = new ReassignmentEventListener(reassignmentService);
			listener.onAgentWentOffline(new AgentWentOfflineEvent("AGT-005"));
			verify(reassignmentService).replanForOfflineAgent("AGT-005");
		}
	}

	@Nested
	@ExtendWith(MockitoExtension.class)
	@DisplayName("ReassignmentService.replanForOfflineAgent")
	class ReplanTests {

		@Mock
		private AgentRepository agentRepository;
		@Mock
		private OrderRepository orderRepository;
		@Mock
		private ReassignmentSuggestionRepository suggestionRepository;
		@Mock
		private RoutingEngine routingEngine;
		@Mock
		private AgentService agentService;
		@Mock
		private TransactionTemplate transactionTemplate;

		@InjectMocks
		private ReassignmentService reassignmentService;

		@BeforeEach
		void stubTransactionTemplate() {
			lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
				TransactionCallback<?> callback = invocation.getArgument(0);
				return callback.doInTransaction(new SimpleTransactionStatus());
			});
			lenient().doAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				Consumer<TransactionStatus> callback = invocation.getArgument(0);
				callback.accept(new SimpleTransactionStatus());
				return null;
			}).when(transactionTemplate).executeWithoutResult(any());
		}

		@Test
		@DisplayName("Positive: only ASSIGNED/IN_TRANSIT orders for the offline agent get suggestions")
		void replanCreatesSuggestionsForStrandedOrders() {
			Order stranded = order("ORD-004", "AGT-005");
			when(orderRepository.findActiveOrdersForAgent("AGT-005")).thenReturn(List.of(stranded));
			when(orderRepository.findById("ORD-004")).thenReturn(Optional.of(stranded));
			when(agentRepository.findByStatusNot(AgentStatus.OFFLINE))
					.thenReturn(List.of(
							new Agent("AGT-002", "Rahul", 0, AgentStatus.AVAILABLE),
							new Agent("AGT-001", "Priya", 2, AgentStatus.BUSY)));
			when(suggestionRepository.existsByOrderIdAndStatus("ORD-004", SuggestionStatus.PENDING))
					.thenReturn(false);
			when(routingEngine.recommend(eq(stranded), any(), any(RoutingContext.class)))
					.thenReturn(new Recommendation("AGT-002", 0.85, "Recovery pick", "ai"));
			when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
			when(suggestionRepository.save(any(ReassignmentSuggestion.class))).thenAnswer(inv -> {
				ReassignmentSuggestion s = inv.getArgument(0);
				s.setId(10L);
				return s;
			});

			reassignmentService.replanForOfflineAgent("AGT-005");

			ArgumentCaptor<ReassignmentSuggestion> captor = ArgumentCaptor.forClass(ReassignmentSuggestion.class);
			verify(suggestionRepository).save(captor.capture());
			assertThat(captor.getValue().getOrderId()).isEqualTo("ORD-004");
			assertThat(captor.getValue().getTriggerReason()).isEqualTo(TriggerReason.AGENT_OFFLINE);
			assertThat(stranded.getStatus()).isEqualTo(OrderStatus.REASSIGNMENT_PENDING);
		}

		@Test
		@DisplayName("Negative: skips orders that belong to a different agent even if returned")
		void replanIgnoresOrdersNotOwnedByOfflineAgent() {
			Order foreign = order("ORD-007", "AGT-003");
			when(orderRepository.findActiveOrdersForAgent("AGT-002")).thenReturn(List.of(foreign));
			when(agentRepository.findByStatusNot(AgentStatus.OFFLINE))
					.thenReturn(List.of(new Agent("AGT-004", "Kiran", 0, AgentStatus.AVAILABLE)));

			reassignmentService.replanForOfflineAgent("AGT-002");

			verify(routingEngine, never()).recommend(any(), any(), any());
			verify(suggestionRepository, never()).save(any());
		}

		@Test
		@DisplayName("Negative: idempotency skips when any PENDING suggestion exists for the order")
		void replanSkipsWhenPendingSuggestionExists() {
			Order stranded = order("ORD-004", "AGT-005");
			when(orderRepository.findActiveOrdersForAgent("AGT-005")).thenReturn(List.of(stranded));
			when(agentRepository.findByStatusNot(AgentStatus.OFFLINE))
					.thenReturn(List.of(new Agent("AGT-002", "Rahul", 0, AgentStatus.AVAILABLE)));
			when(suggestionRepository.existsByOrderIdAndStatus("ORD-004", SuggestionStatus.PENDING))
					.thenReturn(true);

			reassignmentService.replanForOfflineAgent("AGT-005");

			verify(routingEngine, never()).recommend(any(), any(), any());
			verify(suggestionRepository, never()).save(any());
		}

		@Test
		@DisplayName("Negative: zero eligible agents — persists FALLBACK WARNING")
		void replanHandlesZeroAvailableAgentsGracefully() {
			Order stranded = order("ORD-004", "AGT-005");
			when(orderRepository.findActiveOrdersForAgent("AGT-005")).thenReturn(List.of(stranded));
			when(orderRepository.findById("ORD-004")).thenReturn(Optional.of(stranded));
			when(agentRepository.findByStatusNot(AgentStatus.OFFLINE)).thenReturn(List.of());
			when(suggestionRepository.existsByOrderIdAndStatus("ORD-004", SuggestionStatus.PENDING))
					.thenReturn(false);
			when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
			when(suggestionRepository.save(any(ReassignmentSuggestion.class))).thenAnswer(inv -> inv.getArgument(0));

			reassignmentService.replanForOfflineAgent("AGT-005");

			verify(routingEngine, never()).recommend(any(), any(), any());
			ArgumentCaptor<ReassignmentSuggestion> captor = ArgumentCaptor.forClass(ReassignmentSuggestion.class);
			verify(suggestionRepository).save(captor.capture());
			assertThat(captor.getValue().getStrategyUsed()).isEqualTo(ReassignmentService.STRATEGY_NO_ELIGIBLE_AGENT);
		}

		@Test
		@DisplayName("Positive: multiple active orders for the same agent each get a suggestion")
		void replanProcessesAllStrandedOrders() {
			Order o1 = order("ORD-004", "AGT-005");
			Order o2 = order("ORD-005", "AGT-005");
			when(orderRepository.findActiveOrdersForAgent("AGT-005")).thenReturn(List.of(o1, o2));
			when(orderRepository.findById("ORD-004")).thenReturn(Optional.of(o1));
			when(orderRepository.findById("ORD-005")).thenReturn(Optional.of(o2));
			when(agentRepository.findByStatusNot(AgentStatus.OFFLINE))
					.thenReturn(List.of(new Agent("AGT-002", "Rahul", 0, AgentStatus.AVAILABLE)));
			when(suggestionRepository.existsByOrderIdAndStatus(anyString(), eq(SuggestionStatus.PENDING)))
					.thenReturn(false);
			when(routingEngine.recommend(any(), any(), any()))
					.thenReturn(new Recommendation("AGT-002", 0.7, "Least loaded", "rule"));
			when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
			when(suggestionRepository.save(any(ReassignmentSuggestion.class))).thenAnswer(inv -> inv.getArgument(0));

			reassignmentService.replanForOfflineAgent("AGT-005");

			verify(suggestionRepository, times(2)).save(any(ReassignmentSuggestion.class));
			verify(routingEngine, times(2)).recommend(any(), any(), any());
		}

		private Order order(String id, String agentId) {
			Order order = new Order();
			order.setId(id);
			order.setDescription("Test order " + id);
			order.setAssignedAgentId(agentId);
			order.setStatus(OrderStatus.ASSIGNED);
			return order;
		}
	}
}
