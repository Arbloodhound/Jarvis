// Skills and Agents menus for the Jarvis page. Plain JS, no dependencies.
// Loaded from index.html's <head>, before the page's own script removes the
// token from the URL hash, so the token is captured here first.
(() => {
  "use strict";
  let token = "";
  try { token = new URLSearchParams(location.hash.slice(1)).get("token") || ""; } catch { token = ""; }
  if (!token) { try { token = sessionStorage.getItem("jarvis-token") || ""; } catch { token = ""; } }

  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };

  async function api(method, path, body) {
    const res = await fetch(path, {
      method,
      headers: { authorization: `Bearer ${token}`, ...(body ? { "content-type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    let out;
    try { out = await res.json(); } catch { out = { ok: false, error: `HTTP ${res.status}` }; }
    if (!res.ok || !out.ok) throw new Error(out.error || `HTTP ${res.status}`);
    return out;
  }

  let toolCache = null;
  async function toolNames() {
    if (!toolCache) toolCache = (await api("GET", "/api/tools")).tools;
    return toolCache;
  }

  /** Send one chat message through a short-lived socket of our own. */
  function sendChat(text) {
    return new Promise((resolve, reject) => {
      let ws;
      try { ws = new WebSocket(`ws://${location.host}/ws?token=${encodeURIComponent(token)}`); } catch (err) { reject(err); return; }
      let sent = false;
      const timer = setTimeout(() => { try { ws.close(); } catch {} reject(new Error("No answer from Jarvis.")); }, 8000);
      const finish = (err) => { clearTimeout(timer); try { ws.close(); } catch {} if (err) reject(err); else resolve(); };
      ws.onerror = () => finish(new Error("Could not reach Jarvis."));
      ws.onmessage = (m) => {
        let e; try { e = JSON.parse(m.data); } catch { return; }
        if (!sent && e.type === "state") {
          if (e.busy) { finish(new Error("Jarvis is still working on the last request. Stop it or wait, then try again.")); return; }
          sent = true;
          ws.send(JSON.stringify({ type: "send", text }));
          return;
        }
        if (sent && e.type === "user") finish();
        else if (sent && e.type === "error" && !e.runId) finish(new Error(e.message));
      };
    });
  }

  function panel(title) {
    const wrap = el("div", "menu-panel");
    const box = wrap.appendChild(el("div", "box"));
    const head = box.appendChild(el("div", "head"));
    head.appendChild(el("h3", null, title));
    const newBtn = head.appendChild(el("button", "plain", "New"));
    const closeBtn = head.appendChild(el("button", "plain", "Close"));
    newBtn.type = closeBtn.type = "button";
    const err = box.appendChild(el("div", "menu-err"));
    const body = box.appendChild(el("div"));
    const close = () => wrap.classList.remove("open");
    closeBtn.onclick = close;
    wrap.onclick = (ev) => { if (ev.target === wrap) close(); };
    wrap.addEventListener("keydown", (ev) => { if (ev.key === "Escape") close(); });
    document.body.appendChild(wrap);
    return { wrap, body, err, newBtn, open: () => wrap.classList.add("open"), setErr: (m) => { err.textContent = m || ""; } };
  }

  function field(form, label, input) {
    const l = form.appendChild(el("label", null, label));
    l.appendChild(input);
    return input;
  }
  function textInput(value, placeholder) {
    const i = el("input"); i.type = "text"; i.value = value || ""; if (placeholder) i.placeholder = placeholder; return i;
  }
  function textArea(value, placeholder) {
    const t = el("textarea"); t.value = value || ""; if (placeholder) t.placeholder = placeholder; return t;
  }

  // ---------- Skills ----------
  // Panels are built in mount(), once <body> exists (this script runs from <head>).
  let skillsP = null, agentsP = null;

  async function showSkills() {
    skillsP.setErr("");
    skillsP.body.replaceChildren(el("div", "muted", "Loading…"));
    try {
      const { skills } = await api("GET", "/api/skills");
      const list = el("div", "items");
      if (!skills.length) list.appendChild(el("div", "muted", "No skills yet. New creates one in ~/.jarvis/skills."));
      for (const s of skills) {
        const row = list.appendChild(el("div", "item"));
        const txt = row.appendChild(el("div", "txt"));
        const nm = txt.appendChild(el("div", "nm", s.name));
        if (!s.editable) nm.appendChild(el("span", "tag", "read-only"));
        if (!s.eligible) nm.appendChild(el("span", "tag", "not in allowlist"));
        txt.appendChild(el("div", "ds", s.description || "(no description)"));
        if (!s.editable) txt.appendChild(el("div", "ds", s.location));
        const acts = row.appendChild(el("div", "acts"));
        if (s.editable) {
          const ed = acts.appendChild(el("button", "plain", "Edit"));
          const del = acts.appendChild(el("button", "plain danger", "Delete"));
          ed.type = del.type = "button";
          ed.onclick = () => editSkill(s.name);
          del.onclick = async () => {
            if (!confirm(`Delete the skill "${s.name}"? Its folder is moved to ~/.jarvis/deleted/skills.`)) return;
            try { await api("DELETE", `/api/skills/${encodeURIComponent(s.name)}`); await showSkills(); } catch (e) { skillsP.setErr(e.message); }
          };
        }
      }
      skillsP.body.replaceChildren(list);
    } catch (e) {
      skillsP.body.replaceChildren();
      skillsP.setErr(e.message);
    }
  }

  async function editSkill(name) {
    skillsP.setErr("");
    let skill = { name: "", description: "", body: "" };
    if (name) {
      try { skill = (await api("GET", `/api/skills/${encodeURIComponent(name)}`)).skill; } catch (e) { skillsP.setErr(e.message); return; }
    }
    const form = el("form", "edit");
    const nameI = field(form, "Name (lowercase letters, digits, hyphens)", textInput(skill.name, "e.g. weekly-report"));
    if (name) nameI.disabled = true;
    const descI = field(form, "Description (one line: when to use it)", textInput(skill.description));
    const bodyI = field(form, "Instructions (the body of SKILL.md)", textArea(skill.body, "# Steps\n1. …"));
    const btns = form.appendChild(el("div", "row-btns"));
    const cancel = btns.appendChild(el("button", "plain", "Cancel"));
    const save = btns.appendChild(el("button", "primary", "Save"));
    cancel.type = "button"; save.type = "submit";
    cancel.onclick = () => showSkills();
    form.onsubmit = async (ev) => {
      ev.preventDefault();
      skillsP.setErr("");
      const n = name || nameI.value.trim();
      if (!n) { skillsP.setErr("A name is required."); return; }
      try {
        await api("PUT", `/api/skills/${encodeURIComponent(n)}`, { description: descI.value, body: bodyI.value, mode: name ? "update" : "create" });
        await showSkills();
      } catch (e) { skillsP.setErr(e.message); }
    };
    skillsP.body.replaceChildren(form);
    (name ? descI : nameI).focus();
  }

  // ---------- Agents ----------

  async function showAgents() {
    agentsP.setErr("");
    agentsP.body.replaceChildren(el("div", "muted", "Loading…"));
    try {
      const { agents } = await api("GET", "/api/agents");
      const list = el("div", "items");
      if (!agents.length) list.appendChild(el("div", "muted", "No saved agents yet. New creates one in ~/.jarvis/agents."));
      for (const a of agents) {
        const row = list.appendChild(el("div", "item"));
        const txt = row.appendChild(el("div", "txt"));
        txt.appendChild(el("div", "nm", a.name));
        txt.appendChild(el("div", "ds", a.description || "(no description)"));
        txt.appendChild(el("div", "ds", `tools: ${a.tools.length ? a.tools.join(", ") : "default subagent tools"}`));
        if (a.unknownTools.length) txt.appendChild(el("div", "ds menu-err", `unknown (never granted): ${a.unknownTools.join(", ")}`));
        const acts = row.appendChild(el("div", "acts"));
        const runB = acts.appendChild(el("button", "plain", "Run"));
        const ed = acts.appendChild(el("button", "plain", "Edit"));
        const del = acts.appendChild(el("button", "plain danger", "Delete"));
        runB.type = ed.type = del.type = "button";
        runB.onclick = () => runAgent(a.name);
        ed.onclick = () => editAgent(a.name);
        del.onclick = async () => {
          if (!confirm(`Delete the agent "${a.name}"? Its folder is moved to ~/.jarvis/deleted/agents.`)) return;
          try { await api("DELETE", `/api/agents/${encodeURIComponent(a.name)}`); await showAgents(); } catch (e) { agentsP.setErr(e.message); }
        };
      }
      agentsP.body.replaceChildren(list);
    } catch (e) {
      agentsP.body.replaceChildren();
      agentsP.setErr(e.message);
    }
  }

  async function editAgent(name) {
    agentsP.setErr("");
    let agent = { name: "", description: "", tools: [], instructions: "", unknownTools: [] };
    let tools = [];
    try {
      if (name) agent = (await api("GET", `/api/agents/${encodeURIComponent(name)}`)).agent;
      tools = await toolNames();
    } catch (e) { agentsP.setErr(e.message); return; }
    const form = el("form", "edit");
    const nameI = field(form, "Name (lowercase letters, digits, hyphens)", textInput(agent.name, "e.g. price-checker"));
    if (name) nameI.disabled = true;
    const descI = field(form, "Description (one line: what it is for)", textInput(agent.description));
    const tl = form.appendChild(el("label", null, "Tools (none ticked = the default subagent tools; it never gets more than a normal subagent)"));
    const grid = tl.appendChild(el("div", "tools"));
    const boxes = [];
    const known = new Set(tools.map((t) => t.name));
    const addBox = (n, title, unknown) => {
      const l = grid.appendChild(el("label", unknown ? "unknown" : null));
      const c = el("input"); c.type = "checkbox"; c.value = n; c.checked = agent.tools.includes(n);
      l.title = title || "";
      l.appendChild(c); l.appendChild(document.createTextNode(n + (unknown ? " (unknown)" : "")));
      boxes.push(c);
    };
    for (const t of tools) addBox(t.name, t.description, false);
    for (const n of agent.tools) if (!known.has(n)) addBox(n, "Not a subagent tool: kept, never granted", true);
    const instrI = field(form, "Standing instructions (the body of AGENT.md)", textArea(agent.instructions));
    const btns = form.appendChild(el("div", "row-btns"));
    const cancel = btns.appendChild(el("button", "plain", "Cancel"));
    const save = btns.appendChild(el("button", "primary", "Save"));
    cancel.type = "button"; save.type = "submit";
    cancel.onclick = () => showAgents();
    form.onsubmit = async (ev) => {
      ev.preventDefault();
      agentsP.setErr("");
      const n = name || nameI.value.trim();
      if (!n) { agentsP.setErr("A name is required."); return; }
      try {
        await api("PUT", `/api/agents/${encodeURIComponent(n)}`, {
          description: descI.value,
          tools: boxes.filter((b) => b.checked).map((b) => b.value),
          instructions: instrI.value,
          mode: name ? "update" : "create",
        });
        await showAgents();
      } catch (e) { agentsP.setErr(e.message); }
    };
    agentsP.body.replaceChildren(form);
    (name ? descI : nameI).focus();
  }

  function runAgent(name) {
    agentsP.setErr("");
    const box = el("div", "runbox");
    box.appendChild(el("div", "nm", `Run "${name}"`));
    const task = box.appendChild(textArea("", "What should it do? Jarvis will ask you to approve the delegation."));
    const btns = box.appendChild(el("div", "row-btns"));
    const cancel = btns.appendChild(el("button", "plain", "Cancel"));
    const go = btns.appendChild(el("button", "primary", "Send to Jarvis"));
    cancel.type = go.type = "button";
    cancel.onclick = () => showAgents();
    go.onclick = async () => {
      const t = task.value.trim();
      if (!t) { agentsP.setErr("Type a task first."); return; }
      go.disabled = true;
      try {
        await sendChat(`Please spawn the saved agent "${name}" (spawn_agent with agent: "${name}") on this task:\n${t}`);
        agentsP.wrap.classList.remove("open");
      } catch (e) { agentsP.setErr(e.message); go.disabled = false; }
    };
    agentsP.body.replaceChildren(box);
    task.focus();
  }

  // ---------- top-bar buttons ----------
  function mount() {
    const header = document.querySelector("header");
    if (!header) return;
    skillsP = panel("Skills");
    agentsP = panel("Agents");
    skillsP.newBtn.onclick = () => editSkill(null);
    agentsP.newBtn.onclick = () => editAgent(null);
    const anchor = header.querySelector(".spacer");
    const mk = (label, title, onclick) => {
      const b = el("button", "plain", label); b.type = "button"; b.title = title; b.onclick = onclick;
      header.insertBefore(b, anchor);
      return b;
    };
    mk("Skills", "Create, edit and delete skills (~/.jarvis/skills)", () => { skillsP.open(); void showSkills(); }).id = "menu-skills";
    mk("Agents", "Saved subagents: create, edit, delete, run (~/.jarvis/agents)", () => { agentsP.open(); void showAgents(); }).id = "menu-agents";
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", mount);
  else mount();
})();
