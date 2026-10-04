// Build Lab front end. Talks to server/LabServer.java.
(() => {
  "use strict";

  const app = document.getElementById("app");
  const crumbs = document.getElementById("crumbs");
  const progressPill = document.getElementById("progressPill");
  const toastEl = document.getElementById("toast");

  const DIFF_ORDER = { Easy: 0, Medium: 1, Hard: 2 };
  const state = {
    hosted: false, // hosted site: code + progress live in this browser, not on the server
    projects: [],
    progress: {},
    filters: loadPrefs(),
    detail: null, // { project, files, current, buffers, dirty:Set, cm }
  };

  // ------------------------------------------------------------ utils
  function lsGet(key, fallback) {
    try {
      const v = localStorage.getItem(key);
      return v === null ? fallback : JSON.parse(v);
    } catch {
      return fallback;
    }
  }
  function lsSet(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
      return true;
    } catch {
      return false; // private mode / storage full
    }
  }
  function lsRemove(key) {
    try {
      localStorage.removeItem(key);
    } catch { /* ignore */ }
  }
  function loadPrefs() {
    return lsGet("buildlab.filters", {}) || {};
  }
  function savePrefs() {
    lsSet("buildlab.filters", state.filters);
  }
  const codeKey = (id, file) => `buildlab.code.${id}:${file}`;
  function setStatus(id, status) {
    if (status === "todo") delete state.progress[id];
    else state.progress[id] = status;
    if (state.hosted) lsSet("buildlab.progress", state.progress);
    else api("/api/status/" + id, { method: "POST", body: status }).catch(() => {});
    updateProgress();
  }
  function esc(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }
  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.classList.add("show");
    clearTimeout(toast.t);
    toast.t = setTimeout(() => toastEl.classList.remove("show"), 2200);
  }
  async function api(path, opts = {}) {
    const res = await fetch(path, opts);
    const type = res.headers.get("content-type") || "";
    const body = type.includes("json") ? await res.json() : await res.text();
    if (!res.ok) throw new Error((body && body.error) || res.statusText);
    return body;
  }
  function statusOf(id) {
    return state.progress[id] || "todo";
  }
  function renderMarkdown(md) {
    if (window.marked) return window.marked.parse(md);
    return "<pre>" + esc(md) + "</pre>"; // CDN unavailable: still readable
  }
  function updateProgress() {
    const solved = state.projects.filter((p) => statusOf(p.id) === "solved").length;
    progressPill.textContent = `${solved} / ${state.projects.length} solved`;
  }
  const ICONS = {
    build: '<svg viewBox="0 0 24 24" width="20" height="20"><path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/></svg>',
    debug: '<svg viewBox="0 0 24 24" width="20" height="20"><path d="M8 8a4 4 0 0 1 8 0v1H8zM6 10h12v4a6 6 0 0 1-12 0zM12 10v10M3 13h3M18 13h3M4 7l3 2M20 7l-3 2M4 20l3-2M20 20l-3-2" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
  };

  // ------------------------------------------------------------ data
  async function loadCatalog() {
    const config = await api("/api/config").catch(() => ({ mode: "local" }));
    state.hosted = config.mode === "hosted";
    const data = await api("/api/projects");
    state.projects = data.projects;
    state.progress = state.hosted ? lsGet("buildlab.progress", {}) || {} : data.progress || {};
    updateProgress();
  }

  // Hosted site: let people back up / move their browser-stored work.
  function exportBackup() {
    const dump = { version: 1, progress: state.progress, code: {} };
    try {
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (k.startsWith("buildlab.code.")) dump.code[k] = JSON.parse(localStorage.getItem(k));
      }
    } catch { /* storage unavailable */ }
    const blob = new Blob([JSON.stringify(dump, null, 1)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "build-lab-backup.json";
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }
  function importBackup() {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = "application/json";
    input.onchange = async () => {
      try {
        const dump = JSON.parse(await input.files[0].text());
        if (!dump || dump.version !== 1) throw new Error("not a Build Lab backup");
        for (const [k, v] of Object.entries(dump.code || {})) if (k.startsWith("buildlab.code.")) lsSet(k, v);
        state.progress = dump.progress || {};
        lsSet("buildlab.progress", state.progress);
        updateProgress();
        renderList();
        toast("Backup restored");
      } catch (e) {
        toast("Import failed: " + e.message);
      }
    };
    input.click();
  }

  // ------------------------------------------------------------ list view
  function renderList() {
    state.detail = null;
    crumbs.innerHTML = "";
    app.className = "list-page";
    app.innerHTML = '<div class="list-inner"></div>';
    const inner = app.firstChild;
    inner.appendChild(document.getElementById("tpl-list").content.cloneNode(true));

    const topics = [...new Set(state.projects.flatMap((p) => p.tags))].sort();
    const fTopic = inner.querySelector("#fTopic");
    for (const t of topics) fTopic.insertAdjacentHTML("beforeend", `<option>${esc(t)}</option>`);

    const controls = { q: "#q", difficulty: "#fDifficulty", status: "#fStatus", kind: "#fKind", topic: "#fTopic" };
    for (const [key, sel] of Object.entries(controls)) {
      const el = inner.querySelector(sel);
      el.value = state.filters[key] || "";
      el.addEventListener("input", () => {
        state.filters[key] = el.value;
        savePrefs();
        drawCards();
      });
    }
    const sortBtn = inner.querySelector("#sortBtn");
    const paintSort = () => {
      sortBtn.title = state.filters.sort ? "Sorted by difficulty (click for default order)" : "Sort by difficulty";
      sortBtn.classList.toggle("primary", !!state.filters.sort);
    };
    paintSort();
    sortBtn.addEventListener("click", () => {
      state.filters.sort = !state.filters.sort;
      savePrefs();
      paintSort();
      drawCards();
    });

    const next = state.projects.find((p) => statusOf(p.id) !== "solved");
    const startBtn = inner.querySelector("#startBtn");
    if (next) {
      startBtn.href = "#/p/" + next.id;
      startBtn.textContent = statusOf(next.id) === "attempted" ? `Continue: ${next.title}` : `Start: ${next.title}`;
    } else {
      startBtn.textContent = "All solved, nice work";
      startBtn.removeAttribute("href");
    }
    const build = state.projects.filter((p) => p.kind === "build").length;
    inner.querySelector("#heroStats").textContent =
      `${build} build · ${state.projects.length - build} debug · ${state.projects.reduce((a, p) => a + p.minutes, 0)} min total`;
    if (state.hosted) {
      const note = document.createElement("p");
      note.className = "muted small hosted-note";
      note.innerHTML = 'Your code and progress are saved in this browser only. '
        + '<a href="#" id="exportLink">Export a backup</a> · <a href="#" id="importLink">Import</a>';
      inner.querySelector(".hero-text").appendChild(note);
      note.querySelector("#exportLink").addEventListener("click", (e) => { e.preventDefault(); exportBackup(); });
      note.querySelector("#importLink").addEventListener("click", (e) => { e.preventDefault(); importBackup(); });
    }

    drawCards();
  }

  function drawCards() {
    const f = state.filters;
    const q = (f.q || "").trim().toLowerCase();
    let list = state.projects.filter((p) =>
      (!f.difficulty || p.difficulty === f.difficulty) &&
      (!f.status || statusOf(p.id) === f.status) &&
      (!f.kind || p.kind === f.kind) &&
      (!f.topic || p.tags.includes(f.topic)) &&
      (!q || (p.title + " " + p.summary + " " + p.tags.join(" ")).toLowerCase().includes(q)));
    if (f.sort) list = [...list].sort((a, b) => DIFF_ORDER[a.difficulty] - DIFF_ORDER[b.difficulty] || a.minutes - b.minutes);

    const grid = document.getElementById("grid");
    document.getElementById("empty").hidden = list.length > 0;
    grid.innerHTML = list.map((p) => {
      const st = statusOf(p.id);
      const extra = p.tags.length > 3 ? `<span class="chip">+${p.tags.length - 3}</span>` : "";
      const badge = st === "solved" ? '<span class="status-badge solved">✓ Solved</span>'
        : st === "attempted" ? '<span class="status-badge attempted">● Attempted</span>' : "";
      return `<a class="card" href="#/p/${p.id}">
        <div class="card-top">
          <span class="card-icon ${p.kind}">${ICONS[p.kind]}</span>
          <div><h3>${esc(p.title)}</h3><div class="kind">${p.kind === "debug" ? "Debug & fix" : "Build"}</div></div>
        </div>
        <p>${esc(p.summary)}</p>
        <div class="chips">${p.tags.slice(0, 3).map((t) => `<span class="chip">${esc(t)}</span>`).join("")}${extra}</div>
        <div class="card-foot"><span class="diff-${p.difficulty}">${p.difficulty}</span><span class="muted">${p.minutes} min</span>${badge}</div>
      </a>`;
    }).join("");
  }

  // ------------------------------------------------------------ detail view
  async function renderDetail(id) {
    const project = state.projects.find((p) => p.id === id);
    if (!project) return renderList();
    crumbs.innerHTML = `<a href="#/">Projects</a><span class="sep">/</span><strong>${esc(project.title)}</strong>`;
    app.className = "";
    app.innerHTML = "";
    app.appendChild(document.getElementById("tpl-detail").content.cloneNode(true));

    const info = await api("/api/project/" + id);
    const d = {
      project, info,
      current: null,
      buffers: {},
      dirty: new Set(),
      cm: null,
      solutionRevealed: false,
    };
    state.detail = d;

    document.getElementById("pathHint").textContent = state.hosted
      ? "Autosaved in this browser"
      : "Files on disk: " + info.workspacePath;
    if (state.hosted) document.getElementById("saveBtn").hidden = true;
    const statusSel = document.getElementById("statusSel");
    statusSel.value = statusOf(id);
    statusSel.addEventListener("change", () => {
      setStatus(id, statusSel.value);
      toast("Status updated");
    });

    setupLeftTabs(d);
    setupEditor(d);
    setupSplitter();

    document.getElementById("runBtn").addEventListener("click", () => runTests(d));
    document.getElementById("saveBtn").addEventListener("click", () => saveAll(d).then(() => toast("Saved")));
    document.getElementById("resetBtn").addEventListener("click", async () => {
      if (!confirm("Restore the starter code? Your changes to this project will be lost.")) return;
      if (state.hosted) for (const f of info.workspace) lsRemove(codeKey(id, f));
      else await api("/api/reset/" + id, { method: "POST" });
      d.buffers = {};
      d.dirty.clear();
      await openFile(d, d.current, true);
      drawFileTabs(d);
      toast("Starter code restored");
    });

    showLeft(d, "spec");
    drawFileTabs(d);
    const firstFile = await pickInitialFile(d);
    if (firstFile) await openFile(d, firstFile);
  }

  async function pickInitialFile(d) {
    // Open the first file that still has TODOs; otherwise the main service class.
    const files = d.info.workspace;
    for (const f of files) {
      const text = await loadFile(d, f);
      d.buffers[f] = text;
      if (text.includes("TODO")) return f;
    }
    const main = files.find((f) => /(Service|Shortener|Deduplicator|Pipeline|Engine|Gate|Recommender|Repair)\.java$/.test(f));
    return main || files[0];
  }

  /** Current contents of an editable file: browser copy on the hosted site, disk copy locally. */
  async function loadFile(d, f) {
    if (state.hosted) {
      const saved = lsGet(codeKey(d.project.id, f), null);
      if (typeof saved === "string") return saved;
      return api(`/api/file?project=${d.project.id}&area=starter&path=${encodeURIComponent(f)}`);
    }
    return api(`/api/file?project=${d.project.id}&area=workspace&path=${encodeURIComponent(f)}`);
  }

  function setupLeftTabs(d) {
    document.querySelectorAll("#leftTabs button").forEach((b) =>
      b.addEventListener("click", () => showLeft(d, b.dataset.tab)));
  }

  async function showLeft(d, tab) {
    document.querySelectorAll("#leftTabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
    const body = document.getElementById("leftBody");
    const p = d.project;
    if (tab === "spec") {
      body.innerHTML = `<div class="meta-row"><span class="diff-${p.difficulty}">${p.difficulty}</span>
        <span class="muted">${p.minutes} min</span><span class="muted">${p.kind === "debug" ? "Debug & fix" : "Build"}</span>
        <span class="chips">${p.tags.map((t) => `<span class="chip">${esc(t)}</span>`).join("")}</span></div>
        <article class="markdown">${renderMarkdown(d.info.readme)}</article>`;
    } else if (tab === "tests") {
      body.innerHTML = '<p class="muted">The suite your code must pass. Reading tests is part of the job.</p>';
      for (const f of d.info.tests) await appendCode(body, d.project.id, "tests", f);
    } else if (tab === "solution") {
      if (!d.solutionRevealed) {
        body.innerHTML = `<div class="reveal"><p>Try it yourself first. Interviews reward the process, not the answer.
          You can also run the tests against the reference solution without reading it.</p>
          <button class="btn ghost" id="runSolution">Run tests on solution</button>
          <button class="btn primary" id="revealBtn">Show solution</button></div>`;
        body.querySelector("#revealBtn").addEventListener("click", () => {
          d.solutionRevealed = true;
          showLeft(d, "solution");
        });
        body.querySelector("#runSolution").addEventListener("click", () => runTests(d, "solution"));
        return;
      }
      body.innerHTML = "";
      for (const f of d.info.solution) await appendCode(body, d.project.id, "solution", f);
    }
  }

  async function appendCode(container, id, area, file) {
    const text = await api(`/api/file?project=${id}&area=${area}&path=${encodeURIComponent(file)}`);
    const wrap = document.createElement("div");
    wrap.className = "code-view";
    wrap.innerHTML = `<h4>${esc(file)}</h4><pre><code></code></pre>`;
    wrap.querySelector("code").textContent = text;
    container.appendChild(wrap);
  }

  function setupEditor(d) {
    const ta = document.getElementById("editor");
    if (window.CodeMirror) {
      d.cm = window.CodeMirror.fromTextArea(ta, {
        mode: "text/x-java",
        theme: matchMedia("(prefers-color-scheme: light)").matches ? "default" : "material-darker",
        lineNumbers: true,
        indentUnit: 4,
        tabSize: 4,
        matchBrackets: true,
        autoCloseBrackets: true,
        extraKeys: {
          Tab: (cm) => cm.somethingSelected() ? cm.indentSelection("add") : cm.replaceSelection("    "),
          "Ctrl-S": () => saveAll(d).then(() => toast("Saved")),
          "Cmd-S": () => saveAll(d).then(() => toast("Saved")),
          "Ctrl-Enter": () => runTests(d),
          "Cmd-Enter": () => runTests(d),
        },
      });
      d.cm.on("change", () => markDirty(d));
    } else {
      ta.classList.add("fallback");
      ta.spellcheck = false;
      ta.addEventListener("input", () => markDirty(d));
      ta.addEventListener("keydown", (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === "s") { e.preventDefault(); saveAll(d).then(() => toast("Saved")); }
        if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); runTests(d); }
      });
    }
  }

  function editorValue(d) {
    return d.cm ? d.cm.getValue() : document.getElementById("editor").value;
  }
  function setEditorValue(d, text) {
    d.loading = true;
    if (d.cm) {
      d.cm.setValue(text);
      d.cm.clearHistory();
    } else {
      document.getElementById("editor").value = text;
    }
    d.loading = false;
  }
  function markDirty(d) {
    if (d.loading || !d.current) return;
    d.buffers[d.current] = editorValue(d);
    if (state.hosted) {
      // autosave to this browser on every keystroke (cheap: a few KB)
      if (!lsSet(codeKey(d.project.id, d.current), d.buffers[d.current]) && !d.warnedStorage) {
        d.warnedStorage = true;
        toast("This browser won't let the page save. Your work will be lost on reload.");
      }
      return;
    }
    if (!d.dirty.has(d.current)) {
      d.dirty.add(d.current);
      drawFileTabs(d);
    }
  }

  function drawFileTabs(d) {
    const tabs = document.getElementById("fileTabs");
    tabs.innerHTML = d.info.workspace.map((f) => {
      const name = f.split("/").pop();
      return `<button data-file="${esc(f)}" class="${f === d.current ? "active" : ""}" title="${esc(f)}">${esc(name)}${d.dirty.has(f) ? '<span class="dirty">●</span>' : ""}</button>`;
    }).join("");
    tabs.querySelectorAll("button").forEach((b) => b.addEventListener("click", () => openFile(d, b.dataset.file)));
  }

  async function openFile(d, file, forceReload = false) {
    if (!file) return;
    if (d.current) d.buffers[d.current] = editorValue(d);
    d.current = file;
    let text = forceReload ? undefined : d.buffers[file];
    if (text === undefined) {
      text = await loadFile(d, file);
      d.buffers[file] = text;
    }
    setEditorValue(d, text);
    drawFileTabs(d);
    if (d.cm) d.cm.focus();
  }

  async function saveAll(d) {
    if (d.current) d.buffers[d.current] = editorValue(d);
    if (state.hosted) {
      if (d.current) lsSet(codeKey(d.project.id, d.current), d.buffers[d.current]);
      return;
    }
    const files = [...d.dirty];
    for (const f of files) {
      await api(`/api/file?project=${d.project.id}&path=${encodeURIComponent(f)}`, { method: "PUT", body: d.buffers[f] });
      d.dirty.delete(f);
    }
    drawFileTabs(d);
  }

  async function runTests(d, mode = "workspace") {
    const btn = document.getElementById("runBtn");
    const summary = document.getElementById("resultSummary");
    const out = document.getElementById("resultsBody");
    if (btn.disabled) return;
    btn.disabled = true;
    try {
      if (mode === "workspace") await saveAll(d);
      summary.innerHTML = `<span class="spinner"></span> ${mode === "solution" ? "Running tests on the reference solution..." : "Compiling and running tests..."}`;
      out.innerHTML = "";
      let body;
      if (state.hosted && mode === "workspace") {
        const files = {};
        for (const f of d.info.workspace) {
          if (d.buffers[f] === undefined) d.buffers[f] = await loadFile(d, f);
          files[f] = d.buffers[f];
        }
        body = JSON.stringify(files);
      }
      const r = await api(`/api/run/${d.project.id}?mode=${mode}`, {
        method: "POST",
        body,
        headers: body ? { "Content-Type": "application/json" } : undefined,
      });
      if (r.phase === "compile") {
        summary.innerHTML = '<span class="summary-fail">Compilation failed</span>';
        out.innerHTML = `<pre class="compile-out"></pre>`;
        out.firstChild.textContent = r.output;
        return;
      }
      const label = mode === "solution" ? " (reference solution)" : "";
      summary.innerHTML = r.ok
        ? `<span class="summary-pass">All ${r.total} tests passed</span> <span class="muted">${(r.millis / 1000).toFixed(1)}s${label}</span>`
        : `<span class="summary-fail">${r.failed} of ${r.total} failed</span> <span class="muted">${(r.millis / 1000).toFixed(1)}s${label}</span>`;
      const rows = [...r.results].sort((a, b) => (a.status === b.status ? 0 : a.status === "FAIL" ? -1 : 1));
      out.innerHTML = rows.map((t) => `<div class="result-row">
          <span class="dot ${t.status === "PASS" ? "pass" : "fail"}"></span>
          <span>${esc(t.name)}</span><span class="ms">${esc(t.millis)} ms</span>
          ${t.status === "FAIL" ? `<div class="msg">${esc(t.message)}</div>` : ""}
        </div>`).join("");
      if (r.output && r.output.trim()) {
        const pre = document.createElement("pre");
        pre.className = "compile-out";
        pre.style.color = "var(--muted)";
        pre.textContent = r.output;
        out.appendChild(pre);
      }
      if (mode === "workspace") {
        const before = statusOf(d.project.id);
        const after = r.ok ? "solved" : before === "todo" ? "attempted" : before;
        if (state.hosted) setStatus(d.project.id, after);
        else {
          if (after !== "todo") state.progress[d.project.id] = after; // server already recorded it
          updateProgress();
        }
        document.getElementById("statusSel").value = statusOf(d.project.id);
        if (r.ok && before !== "solved") toast("Solved! Nice work.");
      }
    } catch (e) {
      summary.innerHTML = `<span class="summary-fail">Error</span> <span class="muted">${esc(e.message)}</span>`;
    } finally {
      btn.disabled = false;
    }
  }

  function setupSplitter() {
    const splitter = document.getElementById("splitter");
    const left = document.querySelector(".pane.left");
    splitter.addEventListener("mousedown", (e) => {
      e.preventDefault();
      splitter.classList.add("dragging");
      const startX = e.clientX;
      const startW = left.getBoundingClientRect().width;
      const move = (ev) => {
        const w = Math.max(260, Math.min(window.innerWidth - 360, startW + ev.clientX - startX));
        left.style.width = w + "px";
        if (state.detail && state.detail.cm) state.detail.cm.refresh();
      };
      const up = () => {
        splitter.classList.remove("dragging");
        removeEventListener("mousemove", move);
        removeEventListener("mouseup", up);
      };
      addEventListener("mousemove", move);
      addEventListener("mouseup", up);
    });
  }

  // ------------------------------------------------------------ routing
  function hasUnsaved() {
    return state.detail && state.detail.dirty.size > 0;
  }
  window.addEventListener("beforeunload", (e) => {
    if (hasUnsaved()) {
      e.preventDefault();
      e.returnValue = "";
    }
  });

  let lastHash = location.hash;
  async function route() {
    if (hasUnsaved() && !location.hash.startsWith("#/p/" + state.detail.project.id)) {
      if (!confirm("You have unsaved changes. Leave anyway?")) {
        history.replaceState(null, "", lastHash);
        return;
      }
    }
    lastHash = location.hash;
    const m = location.hash.match(/^#\/p\/([a-z0-9-]+)/);
    try {
      if (m) await renderDetail(m[1]);
      else renderList();
    } catch (e) {
      app.innerHTML = `<div class="list-inner"><p class="summary-fail">Could not load: ${esc(e.message)}</p>
        <p class="muted">Is the lab server running? Start it with <code>java server/LabServer.java</code>.</p></div>`;
    }
  }
  window.addEventListener("hashchange", route);

  loadCatalog().then(route).catch((e) => {
    app.innerHTML = `<div class="list-inner"><p class="summary-fail">Could not reach the lab server: ${esc(e.message)}</p>
      <p class="muted">Start it from the practice-lab folder with <code>java server/LabServer.java</code>, then reload.</p></div>`;
  });
})();
