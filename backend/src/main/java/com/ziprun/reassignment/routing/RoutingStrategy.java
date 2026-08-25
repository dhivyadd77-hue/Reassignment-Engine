package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.Order;

import java.util.List;

public interface RoutingStrategy {

	List<Recommendation> recommend(Order order, List<Agent> availableAgents);
}
