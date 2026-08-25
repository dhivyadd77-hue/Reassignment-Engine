# ZipRun AI Reassignment Engine (Sprint 1)

Reactive reassignment for ZipRun: when an agent goes `OFFLINE`, the system queues AI/rule-based suggestions for ops to Accept/Reject.

See [ADR.md](ADR.md) for architecture decisions.

## Layout

```
/backend   Spring Boot 3.4 (Java 17, JPA, H2)
/frontend  React 18 + Vite (ops console)
ADR.md
```

## Quick start (< 5 min)

### Backend

```bash
cd backend
./mvnw spring-boot:run
```

API: http://localhost:8080  
H2 console: http://localhost:8080/h2-console (`jdbc:h2:mem:ziprun`)

Seed loads **5 agents** and **8 orders** automatically.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

UI: http://localhost:5173

### Optional AI

```bash
# Windows PowerShell
$env:LLM_API_KEY="your-gemini-key"
# then set in application.properties or env:
# routing.strategy=ai
```

Default `routing.strategy=rule-based` works offline without an API key. AI failures always fall back to rule-based suggestions.

## Demo path

1. Open the ops console — agents and empty pending list.
2. Click **Set OFFLINE** on `AGT-001` (has 3 assigned orders).
3. Within a few seconds (4s poll), **RE-PLAN** suggestions appear with reasoning.
4. **Accept** one — order becomes `REASSIGNED` to the recommended agent.

## Key APIs

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/agents` | Roster |
| PATCH | `/api/agents/{id}/status` | Set status; `OFFLINE` fires async re-plan |
| GET | `/api/orders?status=` | Orders |
| POST | `/api/orders/{id}/suggest` | On-demand suggestion (`INITIAL`) |
| GET | `/api/suggestions?status=` | Ops inbox |
| PATCH | `/api/suggestions/{id}` | `{ "status": "ACCEPTED" \| "REJECTED" }` |

## Config

```properties
routing.strategy=rule-based   # or rule | ai
llm.provider=gemini
llm.api-key=${LLM_API_KEY:}
```

Default `rule-based` works offline without an API key (`rule-based` aliases to the `rule` bean). Set `routing.strategy=ai` and `LLM_API_KEY` for LLM recommendations; AI failures always fall back to rule-based suggestions.