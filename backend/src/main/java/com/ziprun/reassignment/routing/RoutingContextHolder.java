package com.ziprun.reassignment.routing;

public final class RoutingContextHolder {

	private static final ThreadLocal<RoutingContext> CONTEXT = new ThreadLocal<>();

	private RoutingContextHolder() {
	}

	public static void set(RoutingContext context) {
		CONTEXT.set(context);
	}

	public static RoutingContext get() {
		RoutingContext ctx = CONTEXT.get();
		return ctx != null ? ctx : RoutingContext.initial();
	}

	public static void clear() {
		CONTEXT.remove();
	}
}
