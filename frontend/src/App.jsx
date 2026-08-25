import { useCallback, useEffect, useState } from "react";
import AgentList from "./AgentList";
import { decideSuggestion, fetchAgents, fetchSuggestions, setAgentStatus } from "./api";

const TOAST_SUCCESS = "Dashboard updated with live agent statuses and suggestions.";
const TOAST_REFRESH_ERROR = "Failed to refresh data. Please check connection.";

export default function App() {
  const [agents, setAgents] = useState([]);
  const [suggestions, setSuggestions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState(null);
  const [rejectTarget, setRejectTarget] = useState(null);
  const [modalError, setModalError] = useState(null);
  const [rejecting, setRejecting] = useState(false);
  const [toastMessage, setToastMessage] = useState(null);

  const refresh = useCallback(async () => {
    const [a, s] = await Promise.all([fetchAgents(), fetchSuggestions("PENDING")]);
    setAgents(a);
    setSuggestions(s);
  }, []);

  const loadQuietly = useCallback(async () => {
    try {
      setError(null);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load");
    } finally {
      setLoading(false);
    }
  }, [refresh]);

  const onRefresh = async () => {
    if (isRefreshing) return;
    setIsRefreshing(true);
    setError(null);
    try {
      await refresh();
      setToastMessage({ text: TOAST_SUCCESS, tone: "success" });
    } catch {
      setToastMessage({ text: TOAST_REFRESH_ERROR, tone: "error" });
    } finally {
      setIsRefreshing(false);
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadQuietly();
    const timer = setInterval(() => void loadQuietly(), 4000);
    return () => clearInterval(timer);
  }, [loadQuietly]);

  useEffect(() => {
    if (!toastMessage) return undefined;
    const timer = setTimeout(() => setToastMessage(null), 3000);
    return () => clearTimeout(timer);
  }, [toastMessage]);

  const onAccept = async (id) => {
    setBusyId(id);
    try {
      await decideSuggestion(id, "ACCEPTED");
      setSuggestions((prev) => prev.filter((s) => s.id !== id));
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Decision failed");
    } finally {
      setBusyId(null);
    }
  };

  const openRejectModal = (suggestion) => {
    setModalError(null);
    setRejectTarget(suggestion);
  };

  const closeRejectModal = () => {
    if (rejecting) return;
    setRejectTarget(null);
    setModalError(null);
  };

  const confirmReject = async () => {
    if (!rejectTarget) return;
    setRejecting(true);
    setModalError(null);
    try {
      await decideSuggestion(rejectTarget.id, "REJECTED");
      setSuggestions((prev) => prev.filter((s) => s.id !== rejectTarget.id));
      setRejectTarget(null);
      setToastMessage({ text: "Suggestion rejected successfully.", tone: "success" });
    } catch (err) {
      setModalError(err instanceof Error ? err.message : "Rejection failed");
    } finally {
      setRejecting(false);
    }
  };

  const onAgentStatus = async (agentId, status) => {
    setBusyId(agentId);
    try {
      setError(null);
      const updated = await setAgentStatus(agentId, status);
      setAgents((prev) =>
        prev.map((a) => (a.id === agentId ? { ...a, ...updated } : a)),
      );
      if (status === "OFFLINE") {
        await refresh();
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Status update failed");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="app">
      <header>
        <h1>ZipRun Ops Console</h1>
        <p>
          Real-time dispatch control center. Automated load management (0 → AVAILABLE, ≥1 → BUSY)
          with instant AI-driven emergency re-planning.
        </p>
      </header>

      <div className="toolbar" style={{ marginTop: 16 }}>
        <button
          type="button"
          className="primary refresh-btn"
          onClick={() => void onRefresh()}
          disabled={loading || isRefreshing}
          aria-busy={isRefreshing}
        >
          {isRefreshing ? (
            <>
              <span className="refresh-spinner" aria-hidden="true" />
              Refreshing...
            </>
          ) : loading ? (
            "Loading…"
          ) : (
            "Refresh"
          )}
        </button>
      </div>

      {error ? <div className="error">{error}</div> : null}

      {toastMessage ? (
        <div
          className={`toast toast-${toastMessage.tone}`}
          role="status"
          aria-live="polite"
        >
          {toastMessage.text}
        </div>
      ) : null}

      <div className="grid">
        <section className="panel">
          <h2>Agents</h2>
          <AgentList agents={agents} busyId={busyId} onStatusChange={onAgentStatus} />
        </section>

        <section className="panel">
          <h2>Pending suggestions</h2>
          {suggestions.length === 0 ? (
            <p className="empty">No pending suggestions. Set an agent OFFLINE to trigger re-plans.</p>
          ) : (
            suggestions.map((s) => (
              <article className="card" key={s.id}>
                <div className="meta">
                  <span>
                    Order <strong>{s.orderId}</strong> → <strong>{s.recommendedAgentId}</strong>
                  </span>
                  <span className={`badge ${s.triggerReason === "AGENT_OFFLINE" ? "REPLAN" : "MANUAL"}`}>
                    {s.triggerReason === "AGENT_OFFLINE" ? "RE-PLAN" : "MANUAL"}
                  </span>
                </div>
                <div className="agent-meta">
                  Confidence {(s.confidence * 100).toFixed(0)}%
                  {s.strategyUsed ? ` · ${s.strategyUsed}` : ""}
                </div>
                <p className="reasoning">{s.reasoning}</p>
                <div className="toolbar">
                  <button
                    type="button"
                    className="good"
                    disabled={busyId === s.id}
                    onClick={() => void onAccept(s.id)}
                  >
                    Accept
                  </button>
                  <button
                    type="button"
                    className="danger"
                    disabled={busyId === s.id || rejecting}
                    onClick={() => openRejectModal(s)}
                  >
                    Reject
                  </button>
                </div>
              </article>
            ))
          )}
        </section>
      </div>

      {rejectTarget ? (
        <div
          className="modal-overlay"
          role="presentation"
          onClick={(e) => {
            if (e.target === e.currentTarget) closeRejectModal();
          }}
        >
          <div
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="reject-modal-title"
          >
            <h3 id="reject-modal-title">Confirm Rejection</h3>
            <p className="modal-body">
              Are you sure you want to reject this reassignment suggestion? The order will remain
              stranded in REASSIGNMENT_PENDING state until manually assigned.
            </p>
            <p className="modal-meta">
              Order <strong>{rejectTarget.orderId}</strong>
              {rejectTarget.recommendedAgentId
                ? <> → recommended <strong>{rejectTarget.recommendedAgentId}</strong></>
                : null}
            </p>
            {modalError ? <div className="modal-error">{modalError}</div> : null}
            <div className="modal-actions">
              <button type="button" className="modal-cancel" disabled={rejecting} onClick={closeRejectModal}>
                Cancel
              </button>
              <button
                type="button"
                className="modal-confirm-danger"
                disabled={rejecting}
                onClick={() => void confirmReject()}
              >
                {rejecting ? "Rejecting…" : "Confirm Rejection"}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
