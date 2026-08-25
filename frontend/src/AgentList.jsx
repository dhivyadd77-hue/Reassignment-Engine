import AgentCard from "./AgentCard";

export default function AgentList({ agents, busyId, onStatusChange }) {
  if (agents.length === 0) {
    return <p className="empty">No agents loaded.</p>;
  }

  return (
    <>
      {agents.map((agent) => (
        <AgentCard
          key={agent.id}
          agent={agent}
          busy={busyId === agent.id}
          onStatusChange={onStatusChange}
        />
      ))}
    </>
  );
}
