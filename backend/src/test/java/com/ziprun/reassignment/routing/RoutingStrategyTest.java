package com.ziprun.reassignment.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ziprun.reassignment.ai.LLMGateway;
import com.ziprun.reassignment.ai.PromptFactory;
import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.Order;
import com.ziprun.reassignment.domain.OrderStatus;
import com.ziprun.reassignment.repository.AgentRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Routing strategies")
class RoutingStrategyTest {

	private Order order;
	private List<Agent> roster;

	@BeforeEach
	void setUp() {
		order = new Order();
		order.setId("ORD-100");
		order.setDescription("Electronics — Koramangala to Indiranagar");
		order.setAssignedAgentId("AGT-001");
		order.setStatus(OrderStatus.ASSIGNED);

		roster = List.of(
				new Agent("AGT-001", "Priya", 2, AgentStatus.BUSY),
				new Agent("AGT-002", "Rahul", 0, AgentStatus.AVAILABLE),
				new Agent("AGT-003", "Ananya", 1, AgentStatus.AVAILABLE),
				new Agent("AGT-004", "Kiran", 0, AgentStatus.OFFLINE));
	}

	@AfterEach
	void tearDown() {
		RoutingContextHolder.clear();
	}

	@Nested
	@DisplayName("RuleBasedRoutingStrategy")
	class RuleBasedTests {

		private final RuleBasedRoutingStrategy strategy = new RuleBasedRoutingStrategy();

		@Test
		@DisplayName("Positive: selects non-OFFLINE agent with lowest activeOrderCount (AVAILABLE or BUSY)")
		void selectsLeastLoadedAvailableAgent() {
			// given — BUSY agents remain eligible; OFFLINE is excluded
			RoutingContextHolder.set(RoutingContext.initial());
			List<Agent> eligible = roster.stream()
					.filter(a -> a.getStatus() != AgentStatus.OFFLINE)
					.toList();

			// when
			Recommendation rec = strategy.recommend(order, eligible).get(0);

			// then — AGT-002 (load 0) beats AGT-003 (1) and AGT-001 BUSY (2)
			assertThat(rec.agentId()).isEqualTo("AGT-002");
			assertThat(rec.confidence()).isEqualTo(0.70);
			assertThat(rec.strategyUsed()).isEqualTo("rule");
			assertThat(rec.reasoning()).contains("Rahul");
		}

		@Test
		@DisplayName("Positive: BUSY agents are eligible when they have the lowest load")
		void prefersBusyAgentWhenItHasLowestLoad() {
			RoutingContextHolder.set(RoutingContext.initial());
			List<Agent> eligible = List.of(
					new Agent("AGT-001", "Priya", 0, AgentStatus.BUSY),
					new Agent("AGT-003", "Ananya", 2, AgentStatus.AVAILABLE));

			Recommendation rec = strategy.recommend(order, eligible).get(0);

			assertThat(rec.agentId()).isEqualTo("AGT-001");
		}

		@Test
		@DisplayName("Positive: offline recovery excludes failed agent even if present in list")
		void excludesFailedAgentDuringRecovery() {
			// given
			RoutingContextHolder.set(RoutingContext.offline("AGT-002", 3));
			List<Agent> available = List.of(
					new Agent("AGT-002", "Rahul", 0, AgentStatus.AVAILABLE),
					new Agent("AGT-003", "Ananya", 1, AgentStatus.AVAILABLE));

			// when
			Recommendation rec = strategy.recommend(order, available).get(0);

			// then
			assertThat(rec.agentId()).isEqualTo("AGT-003");
		}
	}

	@Nested
	@DisplayName("AIRoutingStrategy")
	class AiTests {

		@Mock
		private LLMGateway llmGateway;
		@Mock
		private PromptFactory promptFactory;
		@Mock
		private AgentRepository agentRepository;

		private RuleBasedRoutingStrategy ruleBasedRoutingStrategy;
		private AIRoutingStrategy aiRoutingStrategy;

		@BeforeEach
		void initAi() {
			ruleBasedRoutingStrategy = new RuleBasedRoutingStrategy();
			aiRoutingStrategy = new AIRoutingStrategy(
					llmGateway,
					promptFactory,
					ruleBasedRoutingStrategy,
					new ObjectMapper(),
					agentRepository);
			RoutingContextHolder.set(RoutingContext.initial());
			when(promptFactory.buildInitialPrompt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
					.thenReturn("initial-prompt");
		}

		@Test
		@DisplayName("Positive: parses valid LLM JSON, validates against DB, returns AI recommendation")
		void returnsRecommendationFromValidLlmJson() {
			List<Agent> available = List.of(
					new Agent("AGT-002", "Rahul", 0, AgentStatus.AVAILABLE),
					new Agent("AGT-003", "Ananya", 1, AgentStatus.AVAILABLE));
			when(llmGateway.callLLM("initial-prompt")).thenReturn("""
					{"agentId":"AGT-003","confidence":0.91,"reasoning":"Ananya is nearby and has spare capacity."}
					""");
			when(agentRepository.findById("AGT-003"))
					.thenReturn(Optional.of(new Agent("AGT-003", "Ananya", 1, AgentStatus.AVAILABLE)));

			Recommendation rec = aiRoutingStrategy.recommend(order, available).get(0);

			assertThat(rec.agentId()).isEqualTo("AGT-003");
			assertThat(rec.confidence()).isEqualTo(0.91);
			assertThat(rec.reasoning()).contains("spare capacity");
			assertThat(rec.strategyUsed()).isEqualTo("ai");
			verify(llmGateway).callLLM("initial-prompt");
			verify(agentRepository).findById("AGT-003");
		}

		@Test
		@DisplayName("Negative: DB says agent OFFLINE — falls back to RuleBasedRoutingStrategy")
		void fallsBackWhenDbSaysAgentOffline() {
			List<Agent> available = List.of(
					new Agent("AGT-002", "Rahul", 0, AgentStatus.AVAILABLE),
					new Agent("AGT-003", "Ananya", 1, AgentStatus.AVAILABLE));
			when(llmGateway.callLLM(anyString())).thenReturn("""
					{"agentId":"AGT-003","confidence":0.9,"reasoning":"Pick Ananya"}
					""");
			when(agentRepository.findById("AGT-003"))
					.thenReturn(Optional.of(new Agent("AGT-003", "Ananya", 1, AgentStatus.OFFLINE)));

			Recommendation rec = aiRoutingStrategy.recommend(order, available).get(0);

			assertThat(rec.agentId()).isEqualTo("AGT-002");
			assertThat(rec.strategyUsed()).isEqualTo("ai-fallback-rule");
		}

		@Test
		@DisplayName("Negative: hallucinated agent ID falls back to RuleBasedRoutingStrategy")
		void fallsBackWhenLlmReturnsUnknownAgentId() {
			// given
			List<Agent> available = List.of(
					new Agent("AGT-002", "Rahul", 0, AgentStatus.AVAILABLE),
					new Agent("AGT-003", "Ananya", 1, AgentStatus.AVAILABLE));
			when(llmGateway.callLLM(anyString())).thenReturn("""
					{"agentId":"AGT-999","confidence":0.99,"reasoning":"Invented agent"}
					""");

			// when
			Recommendation rec = aiRoutingStrategy.recommend(order, available).get(0);

			// then
			assertThat(rec.agentId()).isEqualTo("AGT-002");
			assertThat(rec.strategyUsed()).isEqualTo("ai-fallback-rule");
			assertThat(rec.reasoning()).startsWith("[AI fallback]");
		}

		@Test
		@DisplayName("Negative: LLM timeout/exception falls back to RuleBasedRoutingStrategy without throwing")
		void fallsBackWhenLlmThrows() {
			// given
			List<Agent> available = List.of(
					new Agent("AGT-002", "Rahul", 0, AgentStatus.AVAILABLE),
					new Agent("AGT-003", "Ananya", 1, AgentStatus.AVAILABLE));
			when(llmGateway.callLLM(anyString())).thenThrow(new RuntimeException("LLM timeout"));

			// when / then
			assertThatCode(() -> {
				Recommendation rec = aiRoutingStrategy.recommend(order, available).get(0);
				assertThat(rec.agentId()).isEqualTo("AGT-002");
				assertThat(rec.strategyUsed()).isEqualTo("ai-fallback-rule");
			}).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("Negative: agent not in available roster (e.g. only OFFLINE candidates) triggers fallback")
		void fallsBackWhenRecommendedAgentNotAvailable() {
			// given — LLM returns AGT-004 who is OFFLINE and not in available list
			List<Agent> available = List.of(new Agent("AGT-002", "Rahul", 0, AgentStatus.AVAILABLE));
			when(llmGateway.callLLM(anyString())).thenReturn("""
					{"agentId":"AGT-004","confidence":0.8,"reasoning":"Pick offline agent"}
					""");

			// when
			Recommendation rec = aiRoutingStrategy.recommend(order, available).get(0);

			// then
			assertThat(rec.agentId()).isEqualTo("AGT-002");
			assertThat(rec.strategyUsed()).isEqualTo("ai-fallback-rule");
		}
	}
}
