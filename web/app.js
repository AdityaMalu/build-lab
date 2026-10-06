// MachineCodingLab front end. Talks to server/LabServer.java.
(() => {
  "use strict";

  const app = document.getElementById("app");
  const crumbs = document.getElementById("crumbs");
  const progressPill = document.getElementById("progressPill");
  const toastEl = document.getElementById("toast");

  const DIFF_ORDER = { Easy: 0, Medium: 1, Hard: 2 };
  const LANG_NAMES = { java: "Java", python: "Python", go: "Go", cpp: "C++" };
  const LANG_SHORT = { java: "Java", python: "Py", go: "Go", cpp: "C++" };
  const state = {
    hosted: false, // hosted site: code + progress live in this browser, not on the server
    projects: [],
    progress: {}, // "lang:projectId" -> status
    projectLangs: {}, // projectId -> languages that project exists in
    serverLangs: ["java"], // languages whose toolchain the server has
    lang: "java",
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
  const codeKey = (id, file) => `buildlab.code.${state.lang}.${id}:${file}`;
  const pkey = (id) => `${state.lang}:${id}`;
  const langQ = () => `lang=${state.lang}`;
  const hasLang = (id) => (state.projectLangs[id] || ["java"]).includes(state.lang);
  function setStatus(id, status) {
    if (status === "todo") delete state.progress[pkey(id)];
    else state.progress[pkey(id)] = status;
    if (state.hosted) lsSet("buildlab.progress", state.progress);
    else api(`/api/status/${id}?${langQ()}`, { method: "POST", body: status }).catch(() => {});
    updateProgress();
  }

  /** Browser storage written before languages existed belongs to Java. */
  function migrateStorage() {
    try {
      const keys = [];
      for (let i = 0; i < localStorage.length; i++) keys.push(localStorage.key(i));
      for (const k of keys) {
        let m = k.match(/^buildlab\.code\.([a-z0-9-]+):(.+)$/);
        if (m) {
          localStorage.setItem(`buildlab.code.java.${m[1]}:${m[2]}`, localStorage.getItem(k));
          localStorage.removeItem(k);
          continue;
        }
        m = k.match(/^buildlab\.results\.([a-z0-9-]+)$/);
        if (m && !Object.keys(LANG_NAMES).includes(m[1])) {
          localStorage.setItem(`buildlab.results.java.${m[1]}`, localStorage.getItem(k));
          localStorage.removeItem(k);
        }
      }
      const progress = lsGet("buildlab.progress", {}) || {};
      let changed = false;
      for (const k of Object.keys(progress)) {
        if (!k.includes(":")) {
          progress["java:" + k] = progress[k];
          delete progress[k];
          changed = true;
        }
      }
      if (changed) lsSet("buildlab.progress", progress);
    } catch { /* storage unavailable */ }
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
    return state.progress[pkey(id)] || "todo";
  }
  // " (waited 2.0s, compile 21.3s, tests 1.2s)": where a run's time went, as reported by the server
  function timingText(t) {
    if (!t) return "";
    const s = (ms) => (ms / 1000).toFixed(1) + "s";
    const parts = [];
    if (t.queueMs >= 500) parts.push("waited " + s(t.queueMs));
    if (t.compileMs >= 500) parts.push("compile " + s(t.compileMs));
    if (t.execMs > 0) parts.push("tests " + s(t.execMs));
    return parts.length > 1 ? ` (${parts.join(", ")})` : "";
  }
  function renderMarkdown(md) {
    if (window.marked) return window.marked.parse(md);
    return "<pre>" + esc(md) + "</pre>"; // CDN unavailable: still readable
  }
  function updateProgress() {
    const available = state.projects.filter((p) => hasLang(p.id));
    const solved = available.filter((p) => statusOf(p.id) === "solved").length;
    progressPill.textContent = `${solved} / ${available.length} solved in ${LANG_NAMES[state.lang]}`;
  }

  function setupLanguagePicker() {
    const sel = document.getElementById("langSel");
    sel.innerHTML = Object.entries(LANG_NAMES).map(([k, v]) => {
      const count = state.projects.filter((p) => (state.projectLangs[p.id] || ["java"]).includes(k)).length;
      const missing = !state.serverLangs.includes(k);
      return `<option value="${k}"${missing ? " disabled" : ""}>${v} · ${count} projects${missing ? " (not installed)" : ""}</option>`;
    }).join("");
    sel.value = state.lang;
    sel.addEventListener("change", () => {
      if (hasUnsaved() && !confirm("You have unsaved changes. Switch language anyway?")) {
        sel.value = state.lang;
        return;
      }
      if (state.detail) state.detail.dirty.clear();
      state.lang = sel.value;
      lsSet("buildlab.lang", state.lang);
      updateProgress();
      route();
    });
  }
  const ICONS = {
    build: '<svg viewBox="0 0 24 24" width="20" height="20"><path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/></svg>',
    debug: '<svg viewBox="0 0 24 24" width="20" height="20"><path d="M8 8a4 4 0 0 1 8 0v1H8zM6 10h12v4a6 6 0 0 1-12 0zM12 10v10M3 13h3M18 13h3M4 7l3 2M20 7l-3 2M4 20l3-2M20 20l-3-2" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
  };

  // ------------------------------------------------------------ data
  async function loadCatalog() {
    const config = await api("/api/config").catch(() => ({ mode: "local", langs: ["java"] }));
    state.hosted = config.mode === "hosted";
    state.serverLangs = config.langs || ["java"];
    if (state.hosted) migrateStorage();
    const data = await api("/api/projects");
    state.projects = data.projects;
    state.projectLangs = data.languages || {};
    state.progress = state.hosted ? lsGet("buildlab.progress", {}) || {} : data.progress || {};
    const saved = lsGet("buildlab.lang", "java");
    state.lang = state.serverLangs.includes(saved) ? saved : "java";
    setupLanguagePicker();
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
    a.download = "machinecodinglab-backup.json";
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
        if (!dump || dump.version !== 1) throw new Error("not a MachineCodingLab backup");
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

    const next = state.projects.find((p) => hasLang(p.id) && statusOf(p.id) !== "solved");
    const startBtn = inner.querySelector("#startBtn");
    if (next) {
      startBtn.href = "/p/" + next.id;
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
      const available = hasLang(p.id);
      const badge = !available ? `<span class="status-badge muted">Not in ${LANG_NAMES[state.lang]} yet</span>`
        : st === "solved" ? '<span class="status-badge solved">✓ Solved</span>'
        : st === "attempted" ? '<span class="status-badge attempted">● Attempted</span>' : "";
      const langs = (state.projectLangs[p.id] || ["java"])
        .map((l) => `<span class="lang-dot${l === state.lang ? " current" : ""}">${LANG_SHORT[l]}</span>`).join("");
      return `<a class="card${available ? "" : " unavailable"}" href="/p/${p.id}">
        <div class="card-langs">${langs}</div>
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
    crumbs.innerHTML = `<a href="/">Projects</a><span class="sep">/</span><strong>${esc(project.title)}</strong>`
      + `<span class="sep">·</span>${LANG_NAMES[state.lang]}`;
    if (!hasLang(id)) return renderUnavailable(project);
    app.className = "";
    app.innerHTML = "";
    app.appendChild(document.getElementById("tpl-detail").content.cloneNode(true));

    const info = await api(`/api/project/${id}?${langQ()}`);
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
      else await api(`/api/reset/${id}?${langQ()}`, { method: "POST" });
      d.buffers = {};
      d.dirty.clear();
      for (const f of info.workspace) d.buffers[f] = await loadFile(d, f);
      await openFile(d, d.current, true);
      drawFileTabs(d);
      d.hash = codeHash(d);
      renderTestList(d);
      toast("Starter code restored");
    });

    d.tests = await api(`/api/tests/${id}?${langQ()}`);
    loadTestSources(d); // for completion and go-to-definition; not awaited
    d.results = lsGet(resultsKey(id), {}) || {};
    d.selected = new Set();
    d.running = null;
    d.compileError = null;
    setupTestControls(d);

    showLeft(d, "spec");
    drawFileTabs(d);
    const firstFile = await pickInitialFile(d); // also loads every file into d.buffers
    if (firstFile) await openFile(d, firstFile);
    d.hash = codeHash(d);
    renderTestList(d);
  }

  async function pickInitialFile(d) {
    // Open the first file that still has TODOs; otherwise the main service class.
    const files = d.info.workspace;
    for (const f of files) d.buffers[f] = await loadFile(d, f);
    const todo = files.find((f) => d.buffers[f].includes("TODO"));
    if (todo) return todo;
    const main = files.find((f) => /(Service|Shortener|Deduplicator|Pipeline|Engine|Gate|Recommender|Repair)\.java$/.test(f));
    return main || files[0];
  }

  /** Current contents of an editable file: browser copy on the hosted site, disk copy locally. */
  async function loadFile(d, f) {
    if (state.hosted) {
      const saved = lsGet(codeKey(d.project.id, f), null);
      if (typeof saved === "string") return saved;
      return api(`/api/file?project=${d.project.id}&${langQ()}&area=starter&path=${encodeURIComponent(f)}`);
    }
    return api(`/api/file?project=${d.project.id}&${langQ()}&area=workspace&path=${encodeURIComponent(f)}`);
  }

  /** The project exists, just not in the selected language yet. */
  function renderUnavailable(project) {
    state.detail = null;
    app.className = "list-page";
    const langs = state.projectLangs[project.id] || ["java"];
    app.innerHTML = `<div class="list-inner"><div class="reveal">
      <h2>${esc(project.title)} isn't available in ${LANG_NAMES[state.lang]} yet</h2>
      <p>It's available in: ${langs.map((l) => LANG_NAMES[l]).join(", ")}.</p>
      ${langs.map((l) => `<button class="btn ghost" data-lang="${l}">Open in ${LANG_NAMES[l]}</button>`).join(" ")}
      <p><a href="/">← Back to all projects</a></p></div></div>`;
    app.querySelectorAll("button[data-lang]").forEach((b) => b.addEventListener("click", () => {
      const sel = document.getElementById("langSel");
      sel.value = b.dataset.lang;
      sel.dispatchEvent(new Event("change"));
    }));
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
      body.innerHTML = '<p class="muted">The suite your code must pass. Reading tests is part of the job. '
        + 'Ctrl+click (Cmd+click on a Mac) a name to jump to its declaration.</p>';
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
    const text = await api(`/api/file?project=${id}&${langQ()}&area=${area}&path=${encodeURIComponent(file)}`);
    const wrap = document.createElement("div");
    wrap.className = "code-view";
    wrap.dataset.file = file;
    wrap.innerHTML = `<h4>${esc(file)}</h4><pre><code></code></pre>`;
    // one element per line so a test can be scrolled to and highlighted
    wrap.querySelector("code").innerHTML = text.split("\n")
      .map((line, i) => `<span class="code-line" data-line="${i + 1}">${esc(line) || " "}</span>`).join("");
    container.appendChild(wrap);
  }

  /** Opens the Tests tab at the given test method and flashes it. */
  async function showTest(d, t) {
    await showLeft(d, "tests");
    const view = [...document.querySelectorAll("#leftBody .code-view")].find((v) => v.dataset.file === t.file);
    if (!view) return;
    // include the annotation line above the method declaration
    const target = view.querySelector(`.code-line[data-line="${Math.max(1, t.line - 1)}"]`);
    const lines = [t.line - 1, t.line].map((n) => view.querySelector(`.code-line[data-line="${n}"]`)).filter(Boolean);
    if (target) target.scrollIntoView({ block: "center" });
    lines.forEach((el) => el.classList.add("flash"));
    setTimeout(() => lines.forEach((el) => el.classList.remove("flash")), 2200);
  }

  // ------------------------------------------------------------ per-test results
  const testKey = (t) => `${t.suite}#${t.method}`;
  const resultsKey = (id) => `buildlab.results.${state.lang}.${id}`;

  /** Cheap fingerprint of the editable code, so results know which version they were run against. */
  function codeHash(d) {
    let h = 0x811c9dc5;
    for (const f of d.info.workspace) {
      const s = f + "\u0000" + (d.buffers[f] ?? "") + "\u0001";
      for (let i = 0; i < s.length; i++) {
        h ^= s.charCodeAt(i);
        h = Math.imul(h, 0x01000193);
      }
    }
    return (h >>> 0).toString(36);
  }

  function isStale(d, r) {
    return r && r.hash !== d.hash;
  }

  function renderTestList(d) {
    const body = document.getElementById("resultsBody");
    const parts = [];
    if (d.compileError) {
      parts.push('<pre class="compile-out" id="compileOut"></pre>');
    }
    const multipleSuites = new Set(d.tests.map((t) => t.suite)).size > 1;
    let lastSuite = null;
    for (const t of d.tests) {
      const key = testKey(t);
      if (multipleSuites && t.suite !== lastSuite) {
        parts.push(`<div class="suite-label">${esc(t.suite)}</div>`);
        lastSuite = t.suite;
      }
      const r = d.results[key];
      const running = d.running && d.running.has(key);
      const dot = running ? "running" : r ? (r.status === "PASS" ? "pass" : "fail") : "none";
      const stale = !running && isStale(d, r);
      parts.push(`<div class="test-row${stale ? " stale" : ""}" data-key="${esc(key)}">
        <input type="checkbox" ${d.selected.has(key) ? "checked" : ""} aria-label="Select ${esc(t.name)}">
        <span class="dot ${dot}" title="${dot === "none" ? "not run yet" : dot}"></span>
        <button class="tname" title="Show this test's code">${esc(t.name)}</button>
        <span class="ms">${r && !running ? esc(r.millis) + " ms" : ""}</span>
        <button class="run1" title="Run just this test" aria-label="Run ${esc(t.name)}">
          <svg viewBox="0 0 24 24" width="12" height="12"><path d="M7 4l13 8-13 8z" fill="currentColor"/></svg>
        </button>
        ${r && r.status === "FAIL" && !running ? `<div class="msg">${esc(r.message)}</div>` : ""}
      </div>`);
    }
    body.innerHTML = parts.join("");
    if (d.compileError) body.querySelector("#compileOut").textContent = d.compileError;

    body.querySelectorAll(".test-row").forEach((row) => {
      const key = row.dataset.key;
      const t = d.tests.find((x) => testKey(x) === key);
      row.querySelector("input").addEventListener("change", (e) => {
        if (e.target.checked) d.selected.add(key);
        else d.selected.delete(key);
        updateTestControls(d);
      });
      row.querySelector(".tname").addEventListener("click", () => showTest(d, t));
      row.querySelector(".run1").addEventListener("click", () => runTests(d, "workspace", [key]));
    });
    updateTestControls(d);
  }

  /** Refreshes the summary line, buttons and select-all box without rebuilding rows. */
  function updateTestControls(d) {
    const total = d.tests.length;
    const fresh = d.tests.map((t) => d.results[testKey(t)]).filter((r) => r && !isStale(d, r));
    const passing = fresh.filter((r) => r.status === "PASS").length;
    const failingKeys = d.tests.filter((t) => d.results[testKey(t)]?.status === "FAIL").map(testKey);
    const staleCount = d.tests.filter((t) => isStale(d, d.results[testKey(t)])).length;
    const summary = document.getElementById("resultSummary");
    if (!d.running) {
      if (d.compileError) summary.innerHTML = '<span class="summary-fail">Compilation failed</span>';
      else if (!Object.keys(d.results).length) summary.textContent = `${total} tests · not run yet`;
      else {
        const cls = passing === total ? "summary-pass" : "summary-fail";
        summary.innerHTML = `<span class="${cls}">${passing} / ${total} passing</span>`
          + (staleCount ? ` <span class="muted">· ${staleCount} out of date</span>` : "")
          + (d.lastRun ? ` <span class="muted">· last run ${(d.lastRun.millis / 1000).toFixed(1)}s${timingText(d.lastRun.timing)}</span>` : "");
      }
    }
    const runFailed = document.getElementById("runFailedBtn");
    runFailed.disabled = !!d.running || failingKeys.length === 0;
    runFailed.textContent = failingKeys.length ? `Run failed (${failingKeys.length})` : "Run failed";
    const runSel = document.getElementById("runSelectedBtn");
    runSel.disabled = !!d.running || d.selected.size === 0;
    runSel.textContent = d.selected.size ? `Run selected (${d.selected.size})` : "Run selected";
    const all = document.getElementById("selectAll");
    all.checked = d.selected.size > 0 && d.selected.size === total;
    all.indeterminate = d.selected.size > 0 && d.selected.size < total;
  }

  /** Called while typing: fade results that no longer match the code. */
  function refreshStaleness(d) {
    clearTimeout(d.staleTimer);
    d.staleTimer = setTimeout(() => {
      d.hash = codeHash(d);
      document.querySelectorAll("#resultsBody .test-row").forEach((row) => {
        row.classList.toggle("stale", !(d.running && d.running.has(row.dataset.key)) && isStale(d, d.results[row.dataset.key]));
      });
      updateTestControls(d);
    }, 250);
  }

  function setupTestControls(d) {
    document.getElementById("selectAll").addEventListener("change", (e) => {
      d.selected = e.target.checked ? new Set(d.tests.map(testKey)) : new Set();
      renderTestList(d);
    });
    document.getElementById("runSelectedBtn").addEventListener("click", () => runTests(d, "workspace", [...d.selected]));
    document.getElementById("runFailedBtn").addEventListener("click", () => runFailed(d));
  }

  function runFailed(d) {
    const keys = d.tests.filter((t) => d.results[testKey(t)]?.status === "FAIL").map(testKey);
    if (keys.length) runTests(d, "workspace", keys);
    else toast("No failing tests to re-run");
  }

  function setupEditor(d) {
    const ta = document.getElementById("editor");
    if (window.CodeMirror) {
      d.cm = window.CodeMirror.fromTextArea(ta, {
        mode: { java: "text/x-java", python: "python", go: "go", cpp: "text/x-c++src" }[state.lang],
        theme: matchMedia("(prefers-color-scheme: light)").matches ? "default" : "material-darker",
        lineNumbers: true,
        indentUnit: 4,
        tabSize: 4,
        indentWithTabs: state.lang === "go", // gofmt style
        matchBrackets: true,
        autoCloseBrackets: true,
        hintOptions: { hint: (cm, opts) => hintAt(d, cm, opts), completeSingle: false, closeCharacters: /[\s()\[\]{};:>,=]/ },
        extraKeys: {
          "Ctrl-Space": (cm) => cm.showHint({ explicit: true }),
          "Cmd-Space": (cm) => cm.showHint({ explicit: true }),
          F12: (cm) => goToDefinition(d, cm.getCursor()),
          "Ctrl-B": (cm) => goToDefinition(d, cm.getCursor()),
          "Cmd-B": (cm) => goToDefinition(d, cm.getCursor()),
          Tab: (cm) => cm.somethingSelected() ? cm.indentSelection("add") : cm.replaceSelection("    "),
          "Ctrl-S": () => saveAll(d).then(() => toast("Saved")),
          "Cmd-S": () => saveAll(d).then(() => toast("Saved")),
          "Ctrl-Enter": () => runTests(d),
          "Cmd-Enter": () => runTests(d),
          "Shift-Ctrl-Enter": () => runFailed(d),
          "Shift-Cmd-Enter": () => runFailed(d),
        },
      });
      d.cm.on("change", () => markDirty(d));
      setupCodeIntel(d);
    } else {
      ta.classList.add("fallback");
      ta.spellcheck = false;
      ta.addEventListener("input", () => markDirty(d));
      ta.addEventListener("keydown", (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === "s") { e.preventDefault(); saveAll(d).then(() => toast("Saved")); }
        if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); e.shiftKey ? runFailed(d) : runTests(d); }
      });
    }
  }

  // ------------------------------------------------------------ completion and go-to-definition
  // Your files and the tests are indexed in the browser (web/codeintel.js). The solution never is.
  const TESTS = "[tests] "; // marks test files in the index

  async function loadTestSources(d) {
    d.testSources = {};
    for (const f of d.info.tests) {
      try {
        d.testSources[f] = await api(`/api/file?project=${d.project.id}&${langQ()}&area=tests&path=${encodeURIComponent(f)}`);
      } catch { /* completion simply knows less */ }
    }
  }

  function intelFiles(d) {
    if (d.current && d.cm) d.buffers[d.current] = editorValue(d);
    const files = {};
    for (const f of d.info.workspace) files[f] = d.buffers[f] ?? "";
    for (const [f, text] of Object.entries(d.testSources || {})) files[TESTS + f] = text;
    return files;
  }

  /** CodeMirror hint source: words that start with what's left of the cursor. */
  function hintAt(d, cm, opts) {
    if (!window.CodeIntel) return null;
    const cur = cm.getCursor();
    const line = cm.getLine(cur.line);
    let start = cur.ch;
    while (start > 0 && /[\w$]/.test(line[start - 1])) start--;
    const prefix = line.slice(start, cur.ch);
    const afterDot = start > 0 && (line[start - 1] === "." || line.slice(start - 2, start) === "->" || line.slice(start - 2, start) === "::");
    if (!opts.explicit && !afterDot && prefix.length < 2) return null;
    const token = cm.getTokenAt(cur);
    if (!opts.explicit && /comment|string/.test(token.type || "")) return null;
    const files = intelFiles(d);
    const list = CodeIntel.completions(CodeIntel.index(files, state.lang), files, state.lang, prefix, afterDot);
    if (!list.length) return null;
    return {
      list: list.map((c) => ({
        text: c.text,
        render: (el) => { el.innerHTML = `<span>${esc(c.text)}</span><span class="hint-kind">${esc(c.kind)}</span>`; },
      })),
      from: window.CodeMirror.Pos(cur.line, start),
      to: window.CodeMirror.Pos(cur.line, cur.ch),
    };
  }

  /** Jumps to where the name at `pos` is declared; repeating it on the same name cycles through matches. */
  function goToDefinition(d, pos, fromFile = d.current) {
    if (!window.CodeIntel) return;
    let name = pos.word;
    if (!name) {
      const range = d.cm.findWordAt(pos);
      name = d.cm.getRange(range.anchor, range.head);
    }
    if (!/^[A-Za-z_$][\w$]*$/.test(name)) return;
    const files = intelFiles(d);
    const hits = CodeIntel.definitions(CodeIntel.index(files, state.lang), name,
      { file: fromFile, line: pos.line, secondary: TESTS });
    if (!hits.length) {
      toast(`No declaration of "${name}" in your code or the tests`);
      return;
    }
    const key = `${name}@${fromFile}:${pos.line}`;
    d.gotoCycle = d.gotoCycle && d.gotoCycle.key === key ? { key, i: (d.gotoCycle.i + 1) % hits.length } : { key, i: 0 };
    let hit = hits[d.gotoCycle.i];
    // already standing on this declaration: go to the next one instead
    if (hits.length > 1 && hit.file === fromFile && hit.line === pos.line) {
      d.gotoCycle.i = (d.gotoCycle.i + 1) % hits.length;
      hit = hits[d.gotoCycle.i];
    }
    if (hits.length > 1) toast(`${name}: ${d.gotoCycle.i + 1} of ${hits.length} declarations (F12 again for the next)`);
    revealSymbol(d, hit);
  }

  async function revealSymbol(d, hit) {
    if (hit.file.startsWith(TESTS)) {
      await showLeft(d, "tests");
      const view = [...document.querySelectorAll("#leftBody .code-view")].find((v) => v.dataset.file === hit.file.slice(TESTS.length));
      const el = view && view.querySelector(`.code-line[data-line="${hit.line + 1}"]`);
      if (!el) return;
      el.scrollIntoView({ block: "center" });
      el.classList.add("flash");
      setTimeout(() => el.classList.remove("flash"), 1600);
      return;
    }
    if (hit.file !== d.current) await openFile(d, hit.file);
    const cm = d.cm;
    cm.setCursor({ line: hit.line, ch: hit.ch });
    cm.scrollIntoView({ line: hit.line, ch: hit.ch }, cm.getScrollInfo().clientHeight / 3);
    cm.focus();
    const handle = cm.addLineClass(hit.line, "background", "cm-goto-flash");
    setTimeout(() => cm.removeLineClass(handle, "background", "cm-goto-flash"), 1600);
  }

  function setupCodeIntel(d) {
    const cm = d.cm;
    // suggestions while typing: after 2 letters of a word, or right after "." / "->" / "::"
    cm.on("inputRead", (editor, change) => {
      if (editor.state.completionActive || change.origin !== "+input") return;
      if (/^[\w$.>:]$/.test(change.text[change.text.length - 1].slice(-1))) editor.showHint();
    });
    // Ctrl+click (Cmd+click on a Mac) goes to the declaration; holding the key underlines the name
    const wrapper = cm.getWrapperElement();
    let mark = null;
    const clearMark = () => { if (mark) { mark.clear(); mark = null; } };
    cm.on("mousedown", (editor, e) => {
      if (!(e.ctrlKey || e.metaKey) || e.button !== 0) return;
      e.preventDefault(); // otherwise CodeMirror adds a second cursor
      clearMark();
      goToDefinition(d, editor.coordsChar({ left: e.clientX, top: e.clientY }));
    });
    wrapper.addEventListener("mousemove", (e) => {
      clearMark();
      if (!(e.ctrlKey || e.metaKey)) return;
      const pos = cm.coordsChar({ left: e.clientX, top: e.clientY });
      const range = cm.findWordAt(pos);
      if (/^[A-Za-z_$][\w$]*$/.test(cm.getRange(range.anchor, range.head))) {
        mark = cm.markText(range.anchor, range.head, { className: "cm-goto-link" });
      }
    });
    wrapper.addEventListener("mouseleave", clearMark);
    wrapper.addEventListener("keyup", (e) => { if (e.key === "Control" || e.key === "Meta") clearMark(); });

    // in the Tests panel too: Ctrl+click a name to jump to it in your code
    document.getElementById("leftBody").addEventListener("click", (e) => {
      if (!(e.ctrlKey || e.metaKey)) return;
      const lineEl = e.target.closest(".code-line");
      const view = e.target.closest(".code-view");
      if (!lineEl || !view || !view.closest("#leftBody") || document.querySelector("#leftTabs .active")?.dataset.tab !== "tests") return;
      const word = wordAtPoint(e.clientX, e.clientY);
      if (!word) return;
      e.preventDefault();
      goToDefinition(d, { line: Number(lineEl.dataset.line) - 1, word }, TESTS + view.dataset.file);
    });
  }

  /** The identifier under the mouse in plain (non-editor) text. */
  function wordAtPoint(x, y) {
    let node, offset;
    if (document.caretPositionFromPoint) {
      const p = document.caretPositionFromPoint(x, y);
      if (!p) return null;
      node = p.offsetNode; offset = p.offset;
    } else if (document.caretRangeFromPoint) {
      const r = document.caretRangeFromPoint(x, y);
      if (!r) return null;
      node = r.startContainer; offset = r.startOffset;
    }
    if (!node || node.nodeType !== Node.TEXT_NODE) return null;
    const text = node.textContent;
    let a = offset, b = offset;
    while (a > 0 && /[\w$]/.test(text[a - 1])) a--;
    while (b < text.length && /[\w$]/.test(text[b])) b++;
    const word = text.slice(a, b);
    return /^[A-Za-z_$][\w$]*$/.test(word) ? word : null;
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
    if (d.tests) refreshStaleness(d);
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
      await api(`/api/file?project=${d.project.id}&${langQ()}&path=${encodeURIComponent(f)}`,
        { method: "PUT", body: d.buffers[f] });
      d.dirty.delete(f);
    }
    drawFileTabs(d);
  }

  /**
   * Runs all tests (keys = null) or just the given "Suite#method" keys.
   * Results are remembered per test together with the code version they ran against.
   */
  async function runTests(d, mode = "workspace", keys = null) {
    if (mode === "solution") return runSolution(d);
    const btn = document.getElementById("runBtn");
    const summary = document.getElementById("resultSummary");
    if (d.running) return;
    const wanted = keys && keys.length ? keys : d.tests.map(testKey);
    const partial = !!(keys && keys.length && keys.length < d.tests.length);
    btn.disabled = true;
    d.running = new Set(wanted);
    d.compileError = null;
    d.extraOutput = null;
    renderTestList(d);
    summary.innerHTML = `<span class="spinner"></span> Running ${wanted.length === 1 ? "1 test" : wanted.length + " tests"}...`;
    try {
      await saveAll(d);
      let body;
      if (state.hosted) {
        const files = {};
        for (const f of d.info.workspace) {
          if (d.buffers[f] === undefined) d.buffers[f] = await loadFile(d, f);
          files[f] = d.buffers[f];
        }
        body = JSON.stringify(files);
      }
      const sentHash = codeHash(d); // after loading unopened files, or results look out of date at once
      const qs = partial ? "&tests=" + encodeURIComponent(wanted.join(",")) : "";
      const r = await api(`/api/run/${d.project.id}?${langQ()}&mode=workspace${qs}`, {
        method: "POST",
        body,
        headers: body ? { "Content-Type": "application/json" } : undefined,
      });

      d.lastRun = r.phase === "compile" ? null : { millis: r.millis, timing: r.timing };
      if (r.phase === "compile") {
        d.compileError = r.output;
      } else {
        const seen = new Set();
        for (const t of r.results) {
          const key = `${t.suite}#${t.method}`;
          seen.add(key);
          d.results[key] = { status: t.status, millis: t.millis, message: t.message, hash: sentHash };
        }
        for (const key of wanted) {
          if (!seen.has(key)) {
            d.results[key] = { status: "FAIL", millis: "-", hash: sentHash,
              message: (r.output || "").trim().split("\n").pop() || "The test did not report a result." };
          }
        }
        if (r.output && r.output.trim()) d.extraOutput = r.output;
        lsSet(resultsKey(d.project.id), d.results);
      }
      d.running = null;
      d.hash = codeHash(d);
      renderTestList(d);
      if (d.extraOutput) {
        const pre = document.createElement("pre");
        pre.className = "compile-out";
        pre.style.color = "var(--muted)";
        pre.textContent = d.extraOutput;
        document.getElementById("resultsBody").appendChild(pre);
      }

      // solved = every test passed against the current code (possibly over several partial runs)
      const before = statusOf(d.project.id);
      const allPass = d.tests.every((t) => {
        const res = d.results[testKey(t)];
        return res && res.status === "PASS" && !isStale(d, res);
      });
      const after = allPass ? "solved" : before === "todo" ? "attempted" : before;
      if (after !== before) setStatus(d.project.id, after);
      document.getElementById("statusSel").value = statusOf(d.project.id);
      if (allPass && before !== "solved") toast("All tests pass. Solved! 🎉");
    } catch (e) {
      d.running = null;
      renderTestList(d);
      summary.innerHTML = `<span class="summary-fail">Error</span> <span class="muted">${esc(e.message)}</span>`;
    } finally {
      d.running = null;
      btn.disabled = false;
      updateTestControls(d);
    }
  }

  /** Runs the suite against the reference solution and shows it temporarily (doesn't touch your results). */
  async function runSolution(d) {
    const btn = document.getElementById("runBtn");
    const summary = document.getElementById("resultSummary");
    const out = document.getElementById("resultsBody");
    if (btn.disabled || d.running) return;
    btn.disabled = true;
    try {
      summary.innerHTML = '<span class="spinner"></span> Running tests on the reference solution...';
      out.innerHTML = "";
      const mode = "solution";
      let body;
      if (state.hosted && mode === "workspace") {
        const files = {};
        for (const f of d.info.workspace) {
          if (d.buffers[f] === undefined) d.buffers[f] = await loadFile(d, f);
          files[f] = d.buffers[f];
        }
        body = JSON.stringify(files);
      }
      const r = await api(`/api/run/${d.project.id}?${langQ()}&mode=${mode}`, {
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
      const time = `<span class="muted">${(r.millis / 1000).toFixed(1)}s${timingText(r.timing)}${label}</span>`;
      summary.innerHTML = r.ok
        ? `<span class="summary-pass">All ${r.total} tests passed</span> ${time}`
        : `<span class="summary-fail">${r.failed} of ${r.total} failed</span> ${time}`;
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
      const back = document.createElement("div");
      back.className = "results-foot";
      back.innerHTML = '<button class="btn ghost sm">← Back to my test results</button>';
      back.querySelector("button").addEventListener("click", () => renderTestList(d));
      out.prepend(back);
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

  // Real URLs: "/" and "/p/<id>" (the server renders them too, for search engines and link previews).
  // Links written as "#/p/<id>" by older versions still work.
  if (/^#\/(p\/[a-z0-9-]+)?/.test(location.hash)) {
    history.replaceState(null, "", location.hash.slice(1) || "/");
  }
  const SITE = "MachineCodingLab";
  let lastPath = location.pathname;
  async function route() {
    if (hasUnsaved() && location.pathname !== "/p/" + state.detail.project.id) {
      if (!confirm("You have unsaved changes. Leave anyway?")) {
        history.pushState(null, "", lastPath);
        return;
      }
    }
    lastPath = location.pathname;
    const m = location.pathname.match(/^\/p\/([a-z0-9-]+)\/?$/);
    try {
      if (m) {
        await renderDetail(m[1]);
        const p = state.projects.find((x) => x.id === m[1]);
        document.title = p ? `${p.title}: machine coding practice | ${SITE}` : SITE;
      } else {
        renderList();
        document.title = `${SITE} | Machine coding & low-level design practice`;
      }
    } catch (e) {
      app.innerHTML = `<div class="list-inner"><p class="summary-fail">Could not load: ${esc(e.message)}</p>
        <p class="muted">Is the lab server running? Start it with <code>java server/LabServer.java</code>.</p></div>`;
    }
  }
  /** Client-side navigation for in-app links; everything else (new tab, other sites, downloads) as usual. */
  document.addEventListener("click", (e) => {
    const a = e.target.closest("a[href^='/']");
    if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const href = a.getAttribute("href");
    if (a.target || a.hasAttribute("download") || !/^\/(p\/[a-z0-9-]+\/?)?$/.test(href)) return;
    e.preventDefault();
    if (href === location.pathname) return;
    history.pushState(null, "", href);
    window.scrollTo(0, 0);
    route();
  });
  window.addEventListener("popstate", route);

  loadCatalog().then(route).catch((e) => {
    app.innerHTML = `<div class="list-inner"><p class="summary-fail">Could not reach the lab server: ${esc(e.message)}</p>
      <p class="muted">Start it from the practice-lab folder with <code>java server/LabServer.java</code>, then reload.</p></div>`;
  });
})();
