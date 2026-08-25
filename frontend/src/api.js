const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8080";

async function parseError(response) {
  try {
    const body = await response.json();
    return body.error || JSON.stringify(body);
  } catch {
    return response.statusText || `HTTP ${response.status}`;
  }
}

export async function fetchAgents() {
  const res = await fetch(`${API_BASE}/api/agents`);
  if (!res.ok) throw new Error(await parseError(res));
  return res.json();
}

export async function fetchSuggestions(status = "PENDING") {
  const res = await fetch(`${API_BASE}/api/suggestions?status=${status}`);
  if (!res.ok) throw new Error(await parseError(res));
  return res.json();
}

export async function decideSuggestion(id, status) {
  const res = await fetch(`${API_BASE}/api/suggestions/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ status }),
  });
  if (!res.ok) throw new Error(await parseError(res));
  return res.json();
}

export async function setAgentStatus(id, status) {
  const res = await fetch(`${API_BASE}/api/agents/${id}/status`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ status }),
  });
  if (!res.ok) throw new Error(await parseError(res));
  return res.json();
}
