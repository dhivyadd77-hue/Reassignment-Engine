package com.ziprun.reassignment.repository;

import com.ziprun.reassignment.domain.Order;
import com.ziprun.reassignment.domain.OrderStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, String> {

	List<Order> findByStatus(OrderStatus status);

	List<Order> findByAssignedAgentIdAndStatusIn(String assignedAgentId, Collection<OrderStatus> statuses);

	long countByAssignedAgentIdAndStatusIn(String assignedAgentId, Collection<OrderStatus> statuses);

	/**
	 * Orders actively owned by an agent for OFFLINE re-plan.
	 * Strictly scoped to this agent — never returns other agents' work.
	 * Only {@link OrderStatus#ASSIGNED} and {@link OrderStatus#IN_TRANSIT}.
	 */
	@Query("""
			select o from Order o
			 where o.assignedAgentId = :agentId
			   and o.status in (com.ziprun.reassignment.domain.OrderStatus.ASSIGNED,
			                    com.ziprun.reassignment.domain.OrderStatus.IN_TRANSIT)
			""")
	List<Order> findActiveOrdersForAgent(@Param("agentId") String agentId);
}
