/* Main report logic. No framework or build step. */
(function () {
  "use strict";

  const DEFAULT_THRESHOLDS = {
    sag_threshold_v: 9.0,
    sag_recover_v: 9.2,
    sag_min_ms: 100,
    peak_window_ms: 100,
    min_voltage_window_ms: 100,
    gap_ms: 200,
    series_hz: 10,
  };

  const state = {
    sessions: [],
    sessionId: null,
    summary: null,
    series: null,
    mode: "simple",
    thresholds: { ...DEFAULT_THRESHOLDS },
    seriesToken: 0,
  };

  const el = (id) => document.getElementById(id);

  function fmt(value, digits = 1) {
    if (typeof value !== "number" || !Number.isFinite(value)) return "—";
    return value.toLocaleString(undefined, {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    });
  }

  function fmtEnergy(value) {
    return fmt(value, Math.abs(value) >= 100 ? 0 : 2);
  }

  function fmtTime(value) {
    return fmt(value, 2);
  }

  function fmtPercent(value) {
    return fmt(value, 0) + "%";
  }

  function fmtDate(iso) {
    if (!iso) return "—";
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return iso;
    return date.toLocaleString(undefined, {
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit",
    });
  }

  function setText(id, text) {
    const node = el(id);
    if (node) node.textContent = text;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function make(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  async function fetchJson(url, options = {}) {
    const response = await fetch(url, { cache: "no-cache", ...options });
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText} for ${url}`);
    }
    return response.json();
  }

  function summaryUrl(entry) {
    return `data/${entry.summary_path}`;
  }

  function seriesUrl(summary) {
    return `data/${summary.series_path}`;
  }

  function sessionLabel(entry) {
    const metadata = entry.metadata || {};
    const event = metadata.event_name || "Practice";
    const match = Number(metadata.match_number) || 0;
    const replay = Number(metadata.replay_number) || 0;
    const type = metadata.match_type || "Practice";
    const energy = entry.headline ? entry.headline.total_energy_wh : null;
    let label;
    if (!match) {
      label = `${event} — practice`;
    } else {
      label = `${event} — ${type} ${match}`;
      if (replay) label += ` replay ${replay}`;
    }
    if (typeof energy === "number") label += ` · ${fmtEnergy(energy)} Wh`;
    if (Number(entry.session_count) > 1) {
      label += ` · part ${Number(entry.session_index) + 1}/${entry.session_count}`;
    }
    return label;
  }

  function currentEntry() {
    return state.sessions.find((entry) => entry.session_id === state.sessionId) || null;
  }

  function updateUrl() {
    if (!state.sessionId) return;
    const params = new URLSearchParams(window.location.search);
    params.set("session", state.sessionId);
    params.set("mode", state.mode);
    const next = `${window.location.pathname}?${params.toString()}`;
    window.history.replaceState(null, "", next);
  }

  function setMode(mode) {
    state.mode = mode === "complex" ? "complex" : "simple";
    updateUrl();
    renderMode();
    if (!state.series) loadSeries();
  }

  function renderMode() {
    const simple = el("mode-simple");
    const complex = el("mode-complex");
    simple.setAttribute("aria-selected", String(state.mode === "simple"));
    complex.setAttribute("aria-selected", String(state.mode === "complex"));
    el("simple-mode").classList.toggle("hidden", state.mode !== "simple");
    el("complex-mode").classList.toggle("hidden", state.mode !== "complex");
    if (state.summary) {
      if (state.mode === "simple") {
        renderSimple();
        renderTimeline();
      } else {
        renderComplex();
      }
    }
  }

  function renderHeader() {
    const summary = state.summary;
    if (!summary) return;
    const metadata = summary.metadata || {};
    const event = metadata.event_name || "Practice";
    const match = Number(metadata.match_number) || 0;
    const replay = Number(metadata.replay_number) || 0;
    const type = metadata.match_type || "Practice";

    setText("match-eyebrow", match ? `${type} match` : "Practice session");
    setText(
      "match-title",
      match ? `${event} — Match ${match}${replay ? ` (replay ${replay})` : ""}` : `${event} — Practice`
    );
    setText(
      "match-subtitle",
      `Processed ${fmtDate(summary.processed_at)} · ${summary.source_file || "log"}`
    );

    const meta = el("match-meta");
    clear(meta);
    const chips = [];
    if (metadata.team_number) chips.push(`Team ${metadata.team_number}`);
    if (metadata.robot_serial) chips.push(metadata.robot_serial);
    if (metadata.match_type) chips.push(metadata.match_type);
    if (summary.session_count > 1) {
      chips.push(`Part ${Number(summary.session_index) + 1} of ${summary.session_count}`);
    }
    if (summary.schema_version) chips.push(`schema v${summary.schema_version}`);
    chips.push(`parser v${summary.parser_version}`);
    chips.forEach((text) => meta.appendChild(make("span", "chip", text)));
  }

  function renderQualityBanner() {
    const summary = state.summary;
    const quality = (summary && summary.data_quality) || {};
    const messages = [];
    if (quality.complete === false) {
      const missing = (quality.missing_keys || []).join(", ");
      messages.push(
        missing
          ? `Incomplete log: missing ${missing}. Metrics may be understated.`
          : "Incomplete log: required power data is missing."
      );
    }
    if (quality.pdh_available === false) {
      messages.push("Total draw is motor-only; static loads are not included.");
    }
    if (Number(quality.gap_count) > 0) {
      messages.push(
        `${quality.gap_count} data gap(s) totalling ${fmtTime(quality.gap_seconds)} s; energy excludes gaps.`
      );
    }
    if (Number(quality.energy_missing_seconds) > 0.05) {
      messages.push(
        `Energy coverage is ${fmtTime(quality.energy_coverage_seconds)} s of ${fmtTime(
          quality.enabled_seconds
        )} enabled seconds.`
      );
    }
    if (Number(quality.motor_current_coverage) < 0.99) {
      messages.push(
        `Motor current coverage is ${fmtPercent(Number(quality.motor_current_coverage) * 100)}.`
      );
    }
    const banner = el("quality-banner");
    if (!messages.length) {
      banner.classList.add("hidden");
      banner.textContent = "";
      return;
    }
    banner.classList.remove("hidden");
    banner.textContent = messages.join(" ");
  }

  function setTile(id, value) {
    setText(id, value);
  }

  function renderSimple() {
    const summary = state.summary;
    if (!summary) return;
    const metrics = summary.metrics || {};
    const quality = summary.data_quality || {};
    const threshold = state.thresholds.sag_threshold_v;

    setTile("tile-energy", fmtEnergy(metrics.total_energy_wh));
    const unmetered = Number(metrics.unmetered_energy_wh) || 0;
    setText(
      "tile-energy-note",
      unmetered > 0.01
        ? `includes ${fmtEnergy(unmetered)} Wh unmetered`
        : "while enabled"
    );
    setTile("tile-time", fmtTime(metrics.enabled_seconds));
    setText(
      "tile-time-note",
      Number(quality.energy_missing_seconds) > 0.05 ? "energy coverage is lower" : "of logged time"
    );
    setTile("tile-voltage", fmt(metrics.lowest_voltage_v, 2));
    setTile("tile-sags", fmt(metrics.sag_count, 0));
    setText("tile-sag-note", `below ${fmt(threshold, 1)} V for ${state.thresholds.sag_min_ms} ms`);
    setTile("tile-current", fmt(metrics.peak_current_a, 0));
    setTile("tile-power", fmt(metrics.peak_power_w, 0));

    renderBars(
      el("subsystem-bars"),
      summary.subsystems || [],
      (item) => item.name,
      "subsystem",
      "Wh"
    );
    renderBars(
      el("motor-bars"),
      summary.motors || [],
      (item) => `${item.subsystem} / ${item.name}`,
      "motor",
      "Wh"
    );
  }

  function renderBars(container, items, labelFn, variant, unit) {
    clear(container);
    if (!items.length) {
      container.appendChild(
        make("p", "empty-note", "No keys were found for this section in the log.")
      );
      return;
    }
    const max = Math.max(...items.map((item) => Number(item.energy_wh) || 0), 1e-9);
    items.forEach((item) => {
      const energy = Number(item.energy_wh) || 0;
      const share = Number(item.share_pct);
      const row = make("div", "bar-row");
      const name = make("div", "bar-name", labelFn(item));
      name.title = labelFn(item);
      const track = make("div", "bar-track");
      const fill = make("div", `bar-fill ${variant === "motor" ? "motor" : ""}`);
      fill.style.width = `${Math.max(2, (energy / max) * 100)}%`;
      track.appendChild(fill);
      const value = make(
        "div",
        "bar-value",
        `${fmtEnergy(energy)} ${unit}${Number.isFinite(share) ? ` · ${fmtPercent(share)}` : ""}`
      );
      row.appendChild(name);
      row.appendChild(track);
      row.appendChild(value);
      container.appendChild(row);
    });
  }

  function renderTimeline() {
    const container = el("simple-timeline");
    const summary = state.summary;
    if (!summary) return;
    if (!state.series) {
      container.textContent = "Loading timeline…";
      setText("timeline-caption", "");
      return;
    }
    const series = state.series;
    const enabled = series.enabled || [];
    const voltage = (series.battery_voltage_v || []).map((value, index) =>
      enabled[index] ? value : null
    );
    PowerCharts.sparkline(container, {
      times: series.time_s || [],
      values: voltage,
      sags: summary.sags || [],
      threshold: { value: state.thresholds.sag_threshold_v },
      height: 150,
      formatY: (value) => `${fmt(value, 1)} V`,
      formatX: (value) => `${fmt(value, 0)} s`,
    });

    const sags = summary.sags || [];
    if (sags.length) {
      const worst = sags.reduce((best, sag) =>
        Number(sag.duration_s) > Number(best.duration_s) ? sag : best
      );
      setText(
        "timeline-caption",
        `${sags.length} sag${sags.length === 1 ? "" : "s"} below ${fmt(
          state.thresholds.sag_threshold_v,
          1
        )} V. Longest: ${fmt(worst.duration_s, 2)} s at ${fmt(worst.min_v, 2)} V (${fmt(
          worst.start_s,
          1
        )}–${fmt(worst.end_s, 1)} s).`
      );
    } else {
      setText(
        "timeline-caption",
        `No voltage sags below ${fmt(state.thresholds.sag_threshold_v, 1)} V. Lowest: ${fmt(
          summary.metrics.lowest_voltage_v,
          2
        )} V.`
      );
    }
  }

  function renderTable(container, columns, rows, emptyText) {
    clear(container);
    if (!rows.length) {
      container.appendChild(make("p", "empty-note", emptyText));
      return;
    }
    const table = document.createElement("table");
    const thead = document.createElement("thead");
    const headRow = document.createElement("tr");
    columns.forEach((column) => {
      const th = document.createElement("th");
      th.scope = "col";
      th.textContent = column.label;
      headRow.appendChild(th);
    });
    thead.appendChild(headRow);
    table.appendChild(thead);

    const tbody = document.createElement("tbody");
    rows.forEach((row) => {
      const tr = document.createElement("tr");
      columns.forEach((column) => {
        const td = document.createElement("td");
        const value = column.value(row);
        td.textContent = value === undefined || value === null ? "—" : String(value);
        if (column.numeric) td.className = "numeric";
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    container.appendChild(table);
  }

  function renderComplex() {
    const summary = state.summary;
    if (!summary) return;
    const metrics = summary.metrics || {};
    const quality = summary.data_quality || {};

    renderTable(
      el("subsystem-table"),
      [
        { label: "Subsystem", value: (row) => row.name },
        { label: "Energy (Wh)", numeric: true, value: (row) => fmtEnergy(row.energy_wh) },
        { label: "Share", numeric: true, value: (row) => fmtPercent(row.share_pct) },
        { label: "Peak current (A)", numeric: true, value: (row) => fmt(row.peak_current_a, 1) },
        { label: "Avg current (A)", numeric: true, value: (row) => fmt(row.average_current_a, 1) },
        { label: "Peak power (W)", numeric: true, value: (row) => fmt(row.peak_power_w, 0) },
      ],
      summary.subsystems || [],
      "No subsystem current keys were found."
    );

    renderTable(
      el("motor-table"),
      [
        { label: "Subsystem", value: (row) => row.subsystem },
        { label: "Motor", value: (row) => row.name },
        { label: "Energy (Wh)", numeric: true, value: (row) => fmtEnergy(row.energy_wh) },
        { label: "Share", numeric: true, value: (row) => fmtPercent(row.share_pct) },
        { label: "Peak current (A)", numeric: true, value: (row) => fmt(row.peak_current_a, 1) },
        { label: "Avg current (A)", numeric: true, value: (row) => fmt(row.average_current_a, 1) },
        { label: "Peak power (W)", numeric: true, value: (row) => fmt(row.peak_power_w, 0) },
      ],
      summary.motors || [],
      "No per-motor current keys were found."
    );

    renderTable(
      el("sag-table"),
      [
        { label: "Start (s)", numeric: true, value: (row) => fmt(row.start_s, 2) },
        { label: "End (s)", numeric: true, value: (row) => fmt(row.end_s, 2) },
        { label: "Duration (s)", numeric: true, value: (row) => fmt(row.duration_s, 2) },
        { label: "Minimum (V)", numeric: true, value: (row) => fmt(row.min_v, 2) },
        { label: "Depth (V)", numeric: true, value: (row) => fmt(row.depth_v, 2) },
      ],
      summary.sags || [],
      "No voltage sags crossed the threshold."
    );

    const qualityList = el("quality-list");
    clear(qualityList);
    const items = [
      ["Complete", quality.complete ? "yes" : "no"],
      ["Sample rate", `${fmt(quality.sample_rate_hz, 1)} Hz`],
      ["Gaps", `${quality.gap_count || 0} (${fmtTime(quality.gap_seconds)} s)`],
      ["Total current source", quality.total_current_source || "—"],
      ["PDH available", quality.pdh_available ? "yes" : "no"],
      ["Subsystems / motors", `${quality.subsystem_count || 0} / ${quality.motor_count || 0}`],
      [
        "Motor current coverage",
        fmtPercent((Number(quality.motor_current_coverage) || 0) * 100),
      ],
      [
        "Energy coverage",
        `${fmtTime(quality.energy_coverage_seconds)} / ${fmtTime(quality.enabled_seconds)} s`,
      ],
      ["Voltage source", quality.voltage_source || "—"],
      ["Enabled source", quality.enabled_source || "—"],
      ["Schema / parser", `${quality.schema_version || 0} / ${quality.parser_version || 0}`],
      [
        "Missing keys",
        (quality.missing_keys || []).length ? quality.missing_keys.join(", ") : "none",
      ],
    ];
    items.forEach(([label, value]) => {
      const item = make("div", "quality-item");
      item.appendChild(make("span", "", label));
      item.appendChild(make("span", "", String(value)));
      qualityList.appendChild(item);
    });

    const rawLink = el("raw-log-link");
    if (summary.raw_url) {
      rawLink.href = summary.raw_url;
      rawLink.classList.remove("hidden");
    } else {
      rawLink.classList.add("hidden");
    }

    if (state.series) {
      renderComplexCharts();
    } else {
      ["chart-voltage", "chart-current", "chart-power", "chart-subsystems"].forEach((id) => {
        el(id).textContent = "Loading chart data…";
      });
    }
  }

  function renderComplexCharts() {
    const series = state.series;
    const summary = state.summary;
    if (!series || !summary) return;
    const times = series.time_s || [];
    const enabled = series.enabled || [];
    const mask = (values) =>
      (values || []).map((value, index) => (enabled[index] ? value : null));
    const threshold = state.thresholds.sag_threshold_v;

    PowerCharts.line(el("chart-voltage"), {
      times,
      series: [
        { name: "Battery voltage", values: mask(series.battery_voltage_v), color: "#2563eb" },
      ],
      threshold: { value: threshold, label: `Sag < ${fmt(threshold, 1)} V` },
      includeZero: false,
      ariaLabel: "Battery voltage over time",
      formatY: (value) => `${fmt(value, 1)} V`,
      formatX: (value) => `${fmt(value, 0)} s`,
      height: 240,
    });

    PowerCharts.line(el("chart-current"), {
      times,
      series: [
        { name: "Total current", values: mask(series.total_current_a), color: "#f97316" },
      ],
      includeZero: true,
      ariaLabel: "Total current over time",
      formatY: (value) => `${fmt(value, 0)} A`,
      formatX: (value) => `${fmt(value, 0)} s`,
      height: 240,
    });

    PowerCharts.line(el("chart-power"), {
      times,
      series: [{ name: "Total power", values: mask(series.total_power_w), color: "#0f766e" }],
      includeZero: true,
      ariaLabel: "Total power over time",
      formatY: (value) => `${fmt(value, 0)} W`,
      formatX: (value) => `${fmt(value, 0)} s`,
      height: 240,
    });

    const subsystemPower = series.subsystem_power_w || {};
    const subsystemSeries = Object.entries(subsystemPower).map(([name, values], index) => ({
      name,
      values: mask(values),
      color: PowerCharts.PALETTE[index % PowerCharts.PALETTE.length],
    }));
    PowerCharts.line(el("chart-subsystems"), {
      times,
      series: subsystemSeries,
      includeZero: true,
      ariaLabel: "Power by subsystem over time",
      formatY: (value) => `${fmt(value, 0)} W`,
      formatX: (value) => `${fmt(value, 0)} s`,
      height: 240,
    });
  }

  async function loadSeries() {
    if (!state.summary) return;
    const token = ++state.seriesToken;
    try {
      const data = await fetchJson(seriesUrl(state.summary));
      if (token !== state.seriesToken || !state.summary) return;
      state.series = data;
      if (state.mode === "simple") renderTimeline();
      else renderComplexCharts();
    } catch (error) {
      if (token !== state.seriesToken) return;
      state.series = null;
      if (state.mode === "simple") {
        el("simple-timeline").textContent = "Could not load timeline data.";
      } else {
        ["chart-voltage", "chart-current", "chart-power", "chart-subsystems"].forEach((id) => {
          el(id).textContent = "Could not load chart data.";
        });
      }
      console.error(error);
    }
  }

  async function loadSession(sessionId) {
    const entry = state.sessions.find((item) => item.session_id === sessionId) || state.sessions[0];
    if (!entry) return;
    state.sessionId = entry.session_id;
    state.summary = null;
    state.series = null;
    state.seriesToken++;
    updateUrl();
    setText("loading", "Loading power report…");
    el("loading").classList.remove("hidden");
    el("error").classList.add("hidden");
    el("content").classList.add("hidden");

    try {
      state.summary = await fetchJson(summaryUrl(entry));
      el("loading").classList.add("hidden");
      el("content").classList.remove("hidden");
      renderHeader();
      renderQualityBanner();
      renderMode();
      loadSeries();
    } catch (error) {
      el("loading").classList.add("hidden");
      const errorBox = el("error");
      errorBox.textContent = `Could not load this session: ${error.message}`;
      errorBox.classList.remove("hidden");
      console.error(error);
    }
  }

  function applyThresholds(data) {
    if (!data) return;
    state.thresholds = { ...state.thresholds, ...data };
    setText(
      "footer-thresholds",
      `Sag threshold ${fmt(state.thresholds.sag_threshold_v, 1)} V · recover ` +
        `${fmt(state.thresholds.sag_recover_v, 1)} V · ${state.thresholds.sag_min_ms} ms · ` +
        `peak ${state.thresholds.peak_window_ms} ms · gap ${state.thresholds.gap_ms} ms`
    );
  }

  async function init() {
    try {
      const [index, thresholds] = await Promise.all([
        fetchJson("data/index.json"),
        fetchJson("config/power-metrics.json").catch(() => null),
      ]);
      applyThresholds(thresholds);
      state.sessions = Array.isArray(index.sessions) ? index.sessions : [];
      if (!state.sessions.length) {
        el("loading").classList.add("hidden");
        const errorBox = el("error");
        errorBox.textContent =
          "No processed sessions yet. Run the uploader after a match, then let the ingest workflow finish.";
        errorBox.classList.remove("hidden");
        return;
      }

      const select = el("session-select");
      clear(select);
      state.sessions.forEach((entry) => {
        const option = document.createElement("option");
        option.value = entry.session_id;
        option.textContent = sessionLabel(entry);
        select.appendChild(option);
      });

      const params = new URLSearchParams(window.location.search);
      const requested = params.get("session");
      const initial = state.sessions.find((entry) => entry.session_id === requested) || state.sessions[0];
      state.mode = params.get("mode") === "complex" ? "complex" : "simple";
      select.value = initial.session_id;
      select.addEventListener("change", (event) => loadSession(event.target.value));
      el("mode-simple").addEventListener("click", () => setMode("simple"));
      el("mode-complex").addEventListener("click", () => setMode("complex"));
      window.addEventListener("popstate", () => {
        const next = new URLSearchParams(window.location.search);
        const nextMode = next.get("mode") === "complex" ? "complex" : "simple";
        const nextSession = next.get("session");
        if (nextSession && nextSession !== state.sessionId) {
          loadSession(nextSession);
        } else if (nextMode !== state.mode) {
          state.mode = nextMode;
          renderMode();
        }
      });

      await loadSession(initial.session_id);
    } catch (error) {
      el("loading").classList.add("hidden");
      const errorBox = el("error");
      errorBox.textContent =
        "Could not load data/index.json. Check that the ingest workflow ran and GitHub Pages is serving the data folder.";
      errorBox.classList.remove("hidden");
      console.error(error);
    }
  }

  document.addEventListener("DOMContentLoaded", init);
})();
