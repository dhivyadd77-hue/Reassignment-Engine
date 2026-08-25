export default function AgentCard({ agent, busy, onStatusChange }) {
  const isOffline = agent.status === "OFFLINE";
  const actionStatus = isOffline ? "AVAILABLE" : "OFFLINE";
  const actionLabel = isOffline ? "Make AVAILABLE" : "Go OFFLINE";

  return (
    <div className="agent">
      <div>
        <strong>{agent.name}</strong>
        <div className="agent-meta">
          {agent.id} · load {agent.activeOrderCount}
          {agent.maxCapacity != null ? ` / ${agent.maxCapacity}` : ""}
          {agent.status === "BUSY" || agent.status === "AVAILABLE" ? " · auto" : ""}
          {isOffline && agent.manualOverride ? " · waiting for drain" : ""}
          {isOffline && !agent.manualOverride && agent.activeOrderCount === 0
            ? " · ready"
            : ""}
        </div>
        <span className={`badge ${agent.status}`}>{agent.status}</span>
      </div>
      <div className="status-actions" role="group" aria-label={`Manual status for ${agent.name}`}>
        <button
          type="button"
          className={`status-btn status-${actionStatus}`}
          disabled={busy}
          onClick={() => onStatusChange(agent.id, actionStatus)}
        >
          {actionLabel}
        </button>
      </div>
    </div>
  );
}
