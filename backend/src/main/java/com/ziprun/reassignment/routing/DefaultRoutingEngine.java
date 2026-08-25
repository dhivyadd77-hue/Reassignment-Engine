package com.ziprun.reassignment.routing;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.Order;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the active {@link RoutingStrategy} from Spring's {@code Map<String, RoutingStrategy>}
 * using {@code routing.strategy} (env override {@code ROUTING_STRATEGY} supported via Environment).
 * <p>
 * Accepted keys: {@code rule}, {@code rule-based} (alias → rule), {@code ai}.
 */
@Component
public class DefaultRoutingEngine implements RoutingEngine {

	private static final Logger log = LoggerFactory.getLogger(DefaultRoutingEngine.class);

	private final Map<String, RoutingStrategy> strategies;
	private final Environment environment;

	public DefaultRoutingEngine(Map<String, RoutingStrategy> strategies, Environment environment) {
		this.strategies = strategies;
		this.environment = environment;
	}

	@PostConstruct
	void validateConfiguredStrategy() {
		String key = activeStrategyKey();
		if (!strategies.containsKey(key)) {
			throw new IllegalStateException(
					"Unknown routing.strategy='" + key + "'. Known: " + strategies.keySet()
							+ " (aliases: rule-based → rule)");
		}
		log.info("Routing strategies registered: {}. Active default: {}", strategies.keySet(), key);
	}

	@Override
	public Recommendation recommend(Order order, List<Agent> availableAgents, RoutingContext context) {
		try {
			RoutingContextHolder.set(context != null ? context : RoutingContext.initial());
			String key = activeStrategyKey();
			RoutingStrategy strategy = strategies.get(key);
			if (strategy == null) {
				throw new IllegalStateException("Unknown routing.strategy=" + key);
			}
			log.info("Routing order={} via strategy={} trigger={}",
					order.getId(), key, RoutingContextHolder.get().triggerReason());
			List<Recommendation> recommendations = strategy.recommend(order, availableAgents);
			if (recommendations == null || recommendations.isEmpty()) {
				throw new IllegalStateException("Strategy " + key + " returned no recommendations");
			}
			return recommendations.get(0);
		} finally {
			RoutingContextHolder.clear();
		}
	}

	private String activeStrategyKey() {
		String raw = environment.getProperty("routing.strategy", "rule");
		String normalized = raw == null ? "rule" : raw.trim().toLowerCase(Locale.ROOT);
		if ("rule-based".equals(normalized) || "rule_based".equals(normalized)) {
			return "rule";
		}
		return normalized;
	}
}
