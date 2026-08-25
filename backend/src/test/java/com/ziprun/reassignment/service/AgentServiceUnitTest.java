package com.ziprun.reassignment.service;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.event.AgentStatusChangedEvent;
import com.ziprun.reassignment.event.AgentWentOfflineEvent;
import com.ziprun.reassignment.event.StatusChangeCause;
import com.ziprun.reassignment.repository.AgentRepository;
import com.ziprun.reassignment.repository.OrderRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentService load-driven AVAILABLE/BUSY + manual OFFLINE")
class AgentServiceUnitTest {

	@Mock
	private AgentRepository agentRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private AgentService agentService;

	@Test
	@DisplayName("Manual OFFLINE sets override and publishes events")
	void manualOfflinePublishesEventsAndSetsOverride() {
		Agent agent = agent("AGT-001", 2, AgentStatus.BUSY, true);
		when(agentRepository.findByIdForUpdate("AGT-001")).thenReturn(Optional.of(agent));
		when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

		Agent result = agentService.applyManualStatus("AGT-001", AgentStatus.OFFLINE);

		assertThat(result.getStatus()).isEqualTo(AgentStatus.OFFLINE);
		assertThat(result.isManualOverride()).isTrue();

		ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
		verify(eventPublisher, atLeast(2)).publishEvent(events.capture());
		assertThat(events.getAllValues()).anyMatch(e -> e instanceof AgentWentOfflineEvent);
		assertThat(events.getAllValues()).anyMatch(e -> e instanceof AgentStatusChangedEvent changed
				&& changed.cause() == StatusChangeCause.MANUAL);
	}

	@Test
	@DisplayName("Manual BUSY is rejected — load rules own BUSY")
	void manualBusyRejected() {
		assertThatThrownBy(() -> agentService.applyManualStatus("AGT-001", AgentStatus.BUSY))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("BUSY is managed automatically");
	}

	@Test
	@DisplayName("Manual AVAILABLE while online is rejected")
	void manualAvailableWhileOnlineRejected() {
		Agent agent = agent("AGT-002", 0, AgentStatus.AVAILABLE, false);
		when(agentRepository.findByIdForUpdate("AGT-002")).thenReturn(Optional.of(agent));

		assertThatThrownBy(() -> agentService.applyManualStatus("AGT-002", AgentStatus.AVAILABLE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Cannot manually set AVAILABLE while agent is online");
	}

	@Test
	@DisplayName("First assign auto-transitions AVAILABLE → BUSY")
	void assignWithLoadAutoBusy() {
		Agent agent = agent("AGT-002", 0, AgentStatus.AVAILABLE, false);
		when(agentRepository.findByIdForUpdate("AGT-002")).thenReturn(Optional.of(agent));
		when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

		Agent result = agentService.onOrderAssigned("AGT-002");

		assertThat(result.getActiveOrderCount()).isEqualTo(1);
		assertThat(result.getStatus()).isEqualTo(AgentStatus.BUSY);
		assertThat(result.isManualOverride()).isFalse();
	}

	@Test
	@DisplayName("OFFLINE ignores auto status on assign; release to 0 clears lock")
	void offlineIgnoresAutoStatusAndClearsLockAtZero() {
		Agent agent = agent("AGT-001", 0, AgentStatus.OFFLINE, true);
		when(agentRepository.findByIdForUpdate("AGT-001")).thenReturn(Optional.of(agent));
		when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

		assertThat(agentService.onOrderAssigned("AGT-001").getStatus()).isEqualTo(AgentStatus.OFFLINE);
		assertThat(agent.getActiveOrderCount()).isEqualTo(1);
		assertThat(agent.isManualOverride()).isTrue();

		Agent afterRelease = agentService.onOrderReleased("AGT-001");
		assertThat(afterRelease.getStatus()).isEqualTo(AgentStatus.OFFLINE);
		assertThat(afterRelease.getActiveOrderCount()).isEqualTo(0);
		assertThat(afterRelease.isManualOverride()).isFalse();
	}

	@Test
	@DisplayName("Release to zero load auto AVAILABLE")
	void releaseToZeroAutoAvailable() {
		Agent agent = agent("AGT-003", 1, AgentStatus.BUSY, false);
		when(agentRepository.findByIdForUpdate("AGT-003")).thenReturn(Optional.of(agent));
		when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

		Agent result = agentService.onOrderReleased("AGT-003");

		assertThat(result.getStatus()).isEqualTo(AgentStatus.AVAILABLE);
		assertThat(result.isManualOverride()).isFalse();
	}

	@Test
	@DisplayName("Make AVAILABLE from OFFLINE with load reconciles to BUSY")
	void makeAvailableWithLoadReconcilesToBusy() {
		Agent agent = agent("AGT-005", 3, AgentStatus.OFFLINE, true);
		when(agentRepository.findByIdForUpdate("AGT-005")).thenReturn(Optional.of(agent));
		when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

		Agent result = agentService.applyManualStatus("AGT-005", AgentStatus.AVAILABLE);

		assertThat(result.getStatus()).isEqualTo(AgentStatus.BUSY);
		assertThat(result.isManualOverride()).isFalse();
	}

	@Test
	@DisplayName("Make AVAILABLE from OFFLINE with zero load restores AVAILABLE")
	void makeAvailableWithZeroLoadRestoresAvailable() {
		Agent agent = agent("AGT-005", 0, AgentStatus.OFFLINE, false);
		when(agentRepository.findByIdForUpdate("AGT-005")).thenReturn(Optional.of(agent));
		when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

		Agent result = agentService.applyManualStatus("AGT-005", AgentStatus.AVAILABLE);

		assertThat(result.getStatus()).isEqualTo(AgentStatus.AVAILABLE);
		assertThat(result.isManualOverride()).isFalse();
		verify(eventPublisher, never()).publishEvent(any(AgentWentOfflineEvent.class));
	}

	@Test
	@DisplayName("syncLoadFromOwnedOrders zeros stranded OFFLINE agent and clears lock")
	@SuppressWarnings("unchecked")
	void syncLoadZerosOfflineAgentAndClearsLock() {
		Agent agent = agent("AGT-001", 3, AgentStatus.OFFLINE, true);
		when(agentRepository.findByIdForUpdate("AGT-001")).thenReturn(Optional.of(agent));
		when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));
		when(orderRepository.countByAssignedAgentIdAndStatusIn(eq("AGT-001"), any(Collection.class)))
				.thenReturn(0L);

		Agent result = agentService.syncLoadFromOwnedOrders("AGT-001");

		assertThat(result.getActiveOrderCount()).isZero();
		assertThat(result.getStatus()).isEqualTo(AgentStatus.OFFLINE);
		assertThat(result.isManualOverride()).isFalse();
	}

	private static Agent agent(String id, int load, AgentStatus status, boolean override) {
		Agent a = new Agent(id, id, load, status);
		a.setMaxCapacity(3);
		a.setManualOverride(override);
		return a;
	}
}
