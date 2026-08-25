# Architecture Decision Records — ZipRun AI Reassignment Engine (Sprint 1)

Format: **Context → Options Considered → Decision → Tradeoffs Accepted**

---

## ADR-1 — Where does routing logic live?

### Context
Routing is invoked from two places: an on-demand HTTP suggest endpoint and an asynchronous agentic re-plan listener after an agent goes `OFFLINE`. Controllers and fat services that mix persistence, events, and picking logic become hard to test and block Sprint 2 strategy additions.

### Options Considered
- **(a) Controllers call rules/LLM directly** — fastest to demo; no reuse across call paths; untestable without MockMvc for every change.
- **(b) Single application service owning persistence + events + routing** — common “god service” smell; every new strategy touches orchestration code.
- **(c) Dedicated `RoutingEngine` + pluggable `RoutingStrategy` beans; application services only orchestrate transactions/events/persistence** — clear boundary; both callers share one façade.

### Decision
Chose **(c)**. Domain entities own status transitions. `RoutingEngine` resolves the active strategy and returns `Recommendation`. `OrderService` / `AgentService` / `SuggestionService` handle JPA and event publishing. Controllers remain thin HTTP adapters.

### Tradeoffs Accepted
More packages/types up front. Acceptable for two call paths and a clean Sprint 2 extension seam. Walkthrough cost: reviewers must open `routing/` instead of a single service class.

**Code seam:** [`backend/src/main/java/com/ziprun/reassignment/routing/DefaultRoutingEngine.java`](backend/src/main/java/com/ziprun/reassignment/routing/DefaultRoutingEngine.java), [`RoutingStrategy.java`](backend/src/main/java/com/ziprun/reassignment/routing/RoutingStrategy.java)

---

## ADR-2 — How is the routing strategy made switchable without code changes?

### Context
Sprint 1 ships `rule` and `ai` strategies. The active strategy must be selectable via configuration. Sprint 2 introduces `ZoneAffinityStrategy`. Both HTTP and async callers must see the same active strategy.

### Options Considered
- **(a) `@Qualifier` / `@Primary` only** — switching requires rebuild or awkward profiles; poor fit for “flip via properties.”
- **(b) Auto-wired `Map<String, RoutingStrategy>` + `routing.strategy` property** — Spring registers beans by name; engine resolves at call time; new strategy = new `@Component("zone")` bean.
- **(c) Manual factory `switch`** — explicit but must edit the factory for every new strategy (Open/Closed violation).

### Decision
Chose **(b)**. Beans: `@Component("rule")`, `@Component("ai")`. `RoutingEngine` reads `routing.strategy` from `Environment` on each call (supports env override `ROUTING_STRATEGY` without rebuilding). `@PostConstruct` validates the configured key exists. Both suggest endpoint and offline listener call only `RoutingEngine`.

### Tradeoffs Accepted
Misconfigured strategy names fail at startup (mitigated by validation). Bean-name map injection is slightly less obvious than a factory — documented in README. Full Spring Cloud live refresh is out of Sprint 1 scope; changing the env/property and relying on call-time `Environment` reads covers the hackathon requirement.

**Code seam:** `Map<String, RoutingStrategy>` in `RoutingEngine`; property `routing.strategy` in [`application.properties`](backend/src/main/resources/application.properties)

---

## ADR-3 — How does the system stay healthy when the LLM is unavailable?

### Context
LLM failures include timeouts, quota/HTTP errors, malformed JSON, and hallucinated agent IDs. The async re-plan path must never silently drop stranded orders.

### Options Considered
- **(a) Fail the HTTP/async flow when AI errors** — leaves ops blind; violates “always surface a suggestion.”
- **(b) Retry-only** — still fails when the provider is down.
- **(c) Validate every AI response; on any failure log and fall back to `RuleBasedRoutingStrategy`, still persist a suggestion.**

### Decision
Chose **(c)**. `AIRoutingStrategy` validates `agentId` against the available-agent list (and excludes the failed agent in recovery). Dual prompts (`PromptFactory.buildInitialPrompt` vs `buildOfflineRecoveryPrompt`) keep `INITIAL` and `AGENT_OFFLINE` reasoning distinct. Fallback suggestions record `strategyUsed=ai-fallback-rule` for transparency.

### Tradeoffs Accepted
During outages ops may see less nuanced, load-based recommendations. Prefer a visible rule suggestion over silence. No circuit breaker in Sprint 1.

**Code seam:** [`ai/PromptFactory.java`](backend/src/main/java/com/ziprun/reassignment/ai/PromptFactory.java), [`routing/AIRoutingStrategy.java`](backend/src/main/java/com/ziprun/reassignment/routing/AIRoutingStrategy.java)

---

## ADR-4 — How is the agentic loop triggered and kept off the request path?

### Context
`PATCH /agents/{id}/status` must return immediately. Re-planning should react to state change, not a timer. The system queues suggestions; ops Accept/Reject is the human checkpoint (no auto-assign). Duplicate OFFLINE flips must not spam suggestions.

### Options Considered
- **(a) Synchronous re-plan inside PATCH** — blocks on LLM; violates non-blocking requirement.
- **(b) Scheduled poller** — delayed, not event-driven.
- **(c) `ApplicationEventPublisher` → `AgentWentOfflineEvent` → `@Async` `@EventListener` with idempotency check.**

### Decision
Chose **(c)**. On OFFLINE: persist agent status, publish event, return. Listener is `@Async("taskExecutor")` + `@TransactionalEventListener(phase = AFTER_COMMIT)`. It loads stranded orders, skips when a `PENDING` + `AGENT_OFFLINE` suggestion already exists (app check + DB unique constraint on `(order_id, status, trigger_reason)`), runs `RoutingEngine` with offline recovery context, persists suggestions, sets orders to `REASSIGNMENT_PENDING`. Zero eligible agents persist a warning suggestion (`strategyUsed=no-eligible-agent`, null recommended agent). Accept path performs the actual reassignment and rejects OFFLINE / null recommendations.

### Tradeoffs Accepted
In-process `@Async` is not durable across JVM crash mid-loop (acceptable for H2 hackathon demo). No Kafka. Idempotency is per PENDING AGENT_OFFLINE suggestion (unique constraint + exists check).

**Code seam:** [`event/AgentWentOfflineEvent.java`](backend/src/main/java/com/ziprun/reassignment/event/AgentWentOfflineEvent.java), [`event/AgentOfflineReplanListener.java`](backend/src/main/java/com/ziprun/reassignment/event/AgentOfflineReplanListener.java)

---

## ADR-5 — Extensibility seams & deliberate exclusions

### Context
Reviewers expect one concrete Sprint 2/3 extension point in code, plus a priority-framed exclusion (not “ran out of time”).

### Options Considered
- **(a) Defer all model placeholders** — forces Sprint 2 migrations under time pressure.
- **(b) Add nullable placeholders now (`currentZone`, `maxCapacity`, `weightClass`, `pickupZone`, `dropoffZone`) and keep `RoutingStrategy` open for `@Component("zone")`.**
- UI: full dispatch/SLA/SSE vs ops floor only.

### Decision
- **Extend:** nullable placeholders on `Agent`/`Order` unused in Sprint 1 logic; Sprint 2 adds `@Component("zone") ZoneAffinityStrategy` and sets `routing.strategy=zone` with **no** `RoutingEngine` changes.
- **Exclude:** full dispatch board, SLA countdown UI, SSE token streaming. Agentic loop correctness and ADR quality are correctness requirements; those UI items are visibility enhancements.
- **Frontend:** React 18 + Vite (`localhost:5173`) for a thin ops floor.

### Tradeoffs Accepted
Slightly wider schema than Sprint 1 needs. UI looks intentionally minimal; demo script focuses on OFFLINE → RE-PLAN badge → Accept.

**Code seam:** nullable fields on `Agent`/`Order`; `RoutingStrategy` interface for future `"zone"` bean.

---

## End-to-End Application Workflow

This is the story of a normal ZipRun day when something goes wrong mid-shift — from morning assignments through ops approval.

```
  Morning assign          Agent OFFLINE              Background re-plan
  (orders → agents)  →  PATCH /agents/{id}/status →  AI / Rule suggest
                                                        │
                                                        ▼
                                              Ops UI: Accept / Reject
                                                        │
                                                        ▼
                                              Order → REASSIGNED
```

### 1. Normal Operations (Morning Baseline)

- Delivery orders start the day already assigned to active agents (`ASSIGNED`).
- Seed data loads 5 agents and 8 pre-assigned orders for the demo.
- Routing is set to AI mode in `application.properties` (`routing.strategy=ai`) so recommendations prefer the LLM when it is healthy.

### 2. Agent Goes Offline (Instant Non-Blocking API)

- An agent becomes unavailable (bike failure, sick call, etc.).
- Ops (or the system) calls `PATCH /agents/{id}/status` with body `{ "status": "OFFLINE" }`.
- The system updates the agent record, publishes an asynchronous `AgentWentOfflineEvent`, and returns **HTTP 200 OK** immediately — typically in well under **50ms**.
- The API does **not** wait for AI or reassignment math; that work happens in the background.

### 3. Background Agentic Loop & Idempotency

- After the database commit succeeds, a background listener picks up the event.
- It finds every order still tied to the offline agent.
- Before creating a suggestion, it checks whether a **PENDING** suggestion with `triggerReason = AGENT_OFFLINE` already exists for that order — if yes, it **skips** (no duplicates if OFFLINE is signaled twice). A DB unique constraint backs the check under concurrency.
- Eligible candidates are **non-OFFLINE** agents (AVAILABLE + BUSY), excluding the failed agent.
- If no eligible agents remain, a **FALLBACK WARNING** suggestion is still persisted (null recommended agent) so ops is not blind.
- Affected orders move to `REASSIGNMENT_PENDING` while they wait for ops.

### 4. AI Re-planning & Resilient Fallback

- For each stranded order, the system builds an **Offline Recovery** prompt (failed agent id, how many orders are stranded, recovery framing — not a first-assignment prompt).
- If the LLM responds successfully, the returned agent id is checked against real available agents in the database (hallucinated ids are rejected).
- If the LLM times out, returns bad JSON, or suggests an invalid agent, the system catches the error and immediately falls back to `RuleBasedRoutingStrategy` — the available agent with the **lowest active load**.
- Either path still produces a recommendation; stranded orders are never silently dropped.

### 5. Ops Dashboard & Human-in-the-Loop Decision

- A `ReassignmentSuggestion` is saved with `status = PENDING`, plus confidence score and plain-English reasoning.
- The Ops UI shows the stranded order, a **RE-PLAN** badge (`AGENT_OFFLINE`), the recommended agent, and the AI (or fallback) explanation.
- An ops manager clicks **Accept** or **Reject** via `PATCH /suggestions/{id}` with `{ "status": "ACCEPTED" }` or `{ "status": "REJECTED" }`.
- **Accept** moves the order to `REASSIGNED` and points it at the new agent (loads updated). **Reject** leaves the suggestion closed without auto-assigning — a person stays in control.

---

## Sprint 1 Architectural Seams & Future Extensibility

| Seam | Sprint 1 artifact | Sprint 2 (Zones & Capacity) | Sprint 3 (SLA Monitoring) | Service/controller churn |
|---|---|---|---|---|
| Strategy | `RoutingStrategy` + `Map<String, RoutingStrategy>` | Add `@Component("zone") ZoneAffinityStrategy` | Reuse active strategy unchanged | **0** — set `routing.strategy=zone` |
| Schema | Nullable `currentZone`, `maxCapacity`, `weightClass`, `pickupZone`, `dropoffZone` | Populate + read in zone strategy | Optional SLA metadata on same columns / adjacent enums | **0 migrations** for named placeholders |
| Pipeline | `@Async` + `AFTER_COMMIT` listener → suggest → ops Accept | Same OFFLINE path | Publish `SLA_BREACH` into same re-plan/suggest flow | **Minimal** — new publisher + enum value |

### Pluggable strategy engine
- Callers use only `RoutingEngine`; beans resolve via Spring `Map<String, RoutingStrategy>` (`rule`, `ai`, …).
- Sprint 2: drop in `ZoneAffinityStrategy` implementing `RoutingStrategy`; flip `routing.strategy=zone`.
- No edits to `ReassignmentService`, controllers, or the async listener.

### Database schema seams
- `Agent.currentZone`, `Agent.maxCapacity` — zone affinity + capacity caps without ALTER in Sprint 2.
- `Order.weightClass`, `Order.pickupZone`, `Order.dropoffZone` — same columns feed zone matching / SLA context in Sprints 2–3.
- Schema already present as nullable; fill values when features go live.

### Event-driven pipeline
- OFFLINE path: persist → publish event → `@Async` `@TransactionalEventListener(AFTER_COMMIT)` → idempotent suggest.
- `TriggerReason` is an open enum seam (`AGENT_OFFLINE` today; add `SLA_BREACH` for Sprint 3).
- Sprint 3 SLA monitor publishes into the **same** re-plan/suggestion/ops-accept pipeline; no controller rewrite.

---

## Decision Log

| ID | Topic | Choice |
|---|---|---|
| ADR-1 | Where routing lives | `RoutingEngine` + pluggable strategies; thin services |
| ADR-2 | Strategy switching | `Map<String, RoutingStrategy>` + `routing.strategy` |
| ADR-3 | LLM unavailable | Validate AI output; fall back to rule-based |
| ADR-4 | Agentic trigger | Domain event + `@Async` after commit; human checkpoint |
| ADR-5 | Extend / exclude | Nullable zone/capacity fields; defer UI ceiling & SSE |
