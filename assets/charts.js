/* Dependency-free SVG charts for the power report. */
(function () {
  const NS = "http://www.w3.org/2000/svg";
  const PALETTE = [
    "#2563eb",
    "#f97316",
    "#0f766e",
    "#8b5cf6",
    "#dc2626",
    "#0891b2",
    "#ca8a04",
    "#db2777",
    "#4d7c0f",
    "#475569",
  ];

  function svgEl(name, attrs) {
    const el = document.createElementNS(NS, name);
    if (attrs) {
      for (const [key, value] of Object.entries(attrs)) {
        el.setAttribute(key, String(value));
      }
    }
    return el;
  }

  function textEl(svg, x, y, text, attrs) {
    const el = svgEl("text", attrs || {});
    el.setAttribute("x", String(x));
    el.setAttribute("y", String(y));
    el.textContent = text;
    svg.appendChild(el);
    return el;
  }

  function isFiniteNumber(value) {
    return typeof value === "number" && Number.isFinite(value);
  }

  function extent(values) {
    let min = Infinity;
    let max = -Infinity;
    for (const value of values) {
      if (!isFiniteNumber(value)) continue;
      if (value < min) min = value;
      if (value > max) max = value;
    }
    return { min, max };
  }

  function niceTicks(min, max, count) {
    if (!Number.isFinite(min) || !Number.isFinite(max)) return [0];
    if (min === max) {
      const pad = Math.abs(min) > 1 ? Math.abs(min) * 0.1 : 1;
      min -= pad;
      max += pad;
    }
    const span = max - min;
    const rawStep = span / Math.max(1, count);
    const power = Math.pow(10, Math.floor(Math.log10(rawStep)));
    const fraction = rawStep / power;
    let step;
    if (fraction <= 1) step = 1;
    else if (fraction <= 2) step = 2;
    else if (fraction <= 5) step = 5;
    else step = 10;
    step *= power;
    const start = Math.ceil(min / step) * step;
    const ticks = [];
    for (let value = start; value <= max + step * 1e-6; value += step) {
      ticks.push(Number(value.toFixed(10)));
    }
    return ticks.length ? ticks : [min, max];
  }

  function lowerBound(times, target) {
    let low = 0;
    let high = times.length;
    while (low < high) {
      const mid = (low + high) >>> 1;
      if (times[mid] < target) low = mid + 1;
      else high = mid;
    }
    return low;
  }

  function pathFor(times, values, xScale, yScale) {
    let d = "";
    let drawing = false;
    for (let i = 0; i < times.length; i++) {
      const value = values[i];
      if (!isFiniteNumber(value)) {
        drawing = false;
        continue;
      }
      const x = xScale(times[i]);
      const y = yScale(value);
      d += (drawing ? " L " : " M ") + x.toFixed(2) + " " + y.toFixed(2);
      drawing = true;
    }
    return d.trim();
  }

  function addLegend(container, series) {
    if (!series || series.length < 2) return;
    const legend = document.createElement("div");
    legend.className = "chart-legend";
    series.forEach((item, index) => {
      const span = document.createElement("span");
      const swatch = document.createElement("i");
      swatch.style.background = item.color || PALETTE[index % PALETTE.length];
      span.appendChild(swatch);
      span.appendChild(document.createTextNode(item.name));
      legend.appendChild(span);
    });
    container.appendChild(legend);
  }

  function line(container, options) {
    container.innerHTML = "";
    const times = options.times || [];
    const seriesInput = options.series || [];
    const series = seriesInput.map((item, index) => ({
      ...item,
      color: item.color || PALETTE[index % PALETTE.length],
    }));
    if (!times.length || !series.length) {
      container.textContent = "No chart data.";
      return;
    }

    const width = 820;
    const height = options.height || 240;
    const margin = { top: 16, right: 18, bottom: 34, left: 58 };
    const plotWidth = width - margin.left - margin.right;
    const plotHeight = height - margin.top - margin.bottom;
    const xMin = times[0];
    const xMax = times[times.length - 1];
    const allValues = series.flatMap((item) => item.values || []);
    if (options.threshold) allValues.push(options.threshold.value);
    const range = extent(allValues);
    let yMin = Number.isFinite(range.min) ? range.min : 0;
    let yMax = Number.isFinite(range.max) ? range.max : 1;
    if (options.includeZero) {
      yMin = Math.min(0, yMin);
      yMax = Math.max(0, yMax);
    }
    if (yMin === yMax) {
      const pad = Math.abs(yMin) > 1 ? Math.abs(yMin) * 0.1 : 1;
      yMin -= pad;
      yMax += pad;
    } else {
      const pad = (yMax - yMin) * 0.08;
      yMin -= pad;
      yMax += pad;
    }

    const xScale = (value) =>
      margin.left + ((value - xMin) / Math.max(1e-9, xMax - xMin)) * plotWidth;
    const yScale = (value) =>
      margin.top + plotHeight - ((value - yMin) / Math.max(1e-9, yMax - yMin)) * plotHeight;

    const svg = svgEl("svg", {
      viewBox: `0 0 ${width} ${height}`,
      role: "img",
      "aria-label": options.ariaLabel || "Time series chart",
    });
    container.appendChild(svg);

    const yTicks = niceTicks(yMin, yMax, 5);
    yTicks.forEach((tick) => {
      const y = yScale(tick);
      svg.appendChild(
        svgEl("line", {
          x1: margin.left,
          x2: width - margin.right,
          y1: y,
          y2: y,
          class: "chart-grid-line",
        })
      );
      textEl(svg, margin.left - 8, y + 3, options.formatY ? options.formatY(tick) : String(tick), {
        "text-anchor": "end",
        class: "chart-axis-label",
      });
    });

    const xTicks = [xMin, xMin + (xMax - xMin) / 2, xMax];
    xTicks.forEach((tick) => {
      const x = xScale(tick);
      textEl(svg, x, height - 10, options.formatX ? options.formatX(tick) : String(tick), {
        "text-anchor": "middle",
        class: "chart-axis-label",
      });
    });

    if (options.threshold) {
      const y = yScale(options.threshold.value);
      svg.appendChild(
        svgEl("line", {
          x1: margin.left,
          x2: width - margin.right,
          y1: y,
          y2: y,
          class: "chart-threshold",
        })
      );
      textEl(svg, width - margin.right, y - 5, options.threshold.label || "", {
        "text-anchor": "end",
        class: "chart-axis-label",
        fill: "#b91c1c",
      });
    }

    series.forEach((item) => {
      const path = svgEl("path", {
        d: pathFor(times, item.values || [], xScale, yScale),
        class: "chart-path",
        stroke: item.color,
      });
      svg.appendChild(path);
    });

    const crosshair = svgEl("line", {
      y1: margin.top,
      y2: margin.top + plotHeight,
      stroke: "#0f172a",
      "stroke-width": 1,
      "stroke-dasharray": "3 3",
      opacity: 0,
    });
    svg.appendChild(crosshair);

    const dots = series.map((item) => {
      const dot = svgEl("circle", {
        r: 3.5,
        fill: item.color,
        stroke: "#fff",
        "stroke-width": 1.5,
        opacity: 0,
      });
      svg.appendChild(dot);
      return dot;
    });

    const overlay = svgEl("rect", {
      x: margin.left,
      y: margin.top,
      width: plotWidth,
      height: plotHeight,
      fill: "transparent",
      style: "cursor: crosshair",
    });
    svg.appendChild(overlay);

    const tooltip = document.createElement("div");
    tooltip.className = "chart-tooltip";
    tooltip.style.display = "none";
    container.appendChild(tooltip);

    function hide() {
      crosshair.setAttribute("opacity", "0");
      dots.forEach((dot) => dot.setAttribute("opacity", "0"));
      tooltip.style.display = "none";
    }

    overlay.addEventListener("pointermove", (event) => {
      const rect = svg.getBoundingClientRect();
      if (!rect.width) return;
      const localX = ((event.clientX - rect.left) / rect.width) * width;
      const clamped = Math.max(margin.left, Math.min(width - margin.right, localX));
      const time = xMin + ((clamped - margin.left) / plotWidth) * (xMax - xMin);
      let index = lowerBound(times, time);
      if (index >= times.length) index = times.length - 1;
      if (index > 0 && Math.abs(times[index - 1] - time) < Math.abs(times[index] - time)) {
        index -= 1;
      }
      const x = xScale(times[index]);
      crosshair.setAttribute("x1", String(x));
      crosshair.setAttribute("x2", String(x));
      crosshair.setAttribute("opacity", "0.6");

      series.forEach((item, seriesIndex) => {
        const value = (item.values || [])[index];
        const dot = dots[seriesIndex];
        if (isFiniteNumber(value)) {
          dot.setAttribute("cx", String(x));
          dot.setAttribute("cy", String(yScale(value)));
          dot.setAttribute("opacity", "1");
        } else {
          dot.setAttribute("opacity", "0");
        }
      });

      tooltip.innerHTML = "";
      const timeLine = document.createElement("div");
      timeLine.className = "tt-time";
      timeLine.textContent = options.formatX ? options.formatX(times[index]) : times[index] + " s";
      tooltip.appendChild(timeLine);

      series.forEach((item) => {
        const value = (item.values || [])[index];
        if (!isFiniteNumber(value)) return;
        const row = document.createElement("div");
        row.className = "tt-row";
        const name = document.createElement("span");
        name.textContent = item.name;
        const val = document.createElement("span");
        val.textContent = options.formatY ? options.formatY(value) : String(value);
        row.appendChild(name);
        row.appendChild(val);
        tooltip.appendChild(row);
      });

      const containerRect = container.getBoundingClientRect();
      tooltip.style.left = `${event.clientX - containerRect.left}px`;
      tooltip.style.top = `${event.clientY - containerRect.top - 12}px`;
      tooltip.style.display = "block";
    });

    overlay.addEventListener("pointerleave", hide);
    addLegend(container, series);
  }

  function sparkline(container, options) {
    container.innerHTML = "";
    const times = options.times || [];
    const values = options.values || [];
    if (!times.length || !values.length) {
      container.textContent = "No timeline data.";
      return;
    }

    const width = 820;
    const height = options.height || 150;
    const margin = { top: 12, right: 12, bottom: 24, left: 12 };
    const plotWidth = width - margin.left - margin.right;
    const plotHeight = height - margin.top - margin.bottom;
    const xMin = times[0];
    const xMax = times[times.length - 1];
    const range = extent(values);
    let yMin = Number.isFinite(range.min) ? range.min : 0;
    let yMax = Number.isFinite(range.max) ? range.max : 1;
    if (yMin === yMax) {
      yMin -= 1;
      yMax += 1;
    } else {
      const pad = (yMax - yMin) * 0.12;
      yMin -= pad;
      yMax += pad;
    }

    const xScale = (value) =>
      margin.left + ((value - xMin) / Math.max(1e-9, xMax - xMin)) * plotWidth;
    const yScale = (value) =>
      margin.top + plotHeight - ((value - yMin) / Math.max(1e-9, yMax - yMin)) * plotHeight;

    const svg = svgEl("svg", {
      viewBox: `0 0 ${width} ${height}`,
      role: "img",
      "aria-label": options.ariaLabel || "Battery voltage timeline",
    });
    container.appendChild(svg);

    svg.appendChild(
      svgEl("rect", {
        x: margin.left,
        y: margin.top,
        width: plotWidth,
        height: plotHeight,
        fill: "#f8fafc",
        rx: 8,
      })
    );

    (options.sags || []).forEach((sag) => {
      const start = Math.max(xMin, sag.start_s);
      const end = Math.min(xMax, sag.end_s);
      if (end <= start) return;
      svg.appendChild(
        svgEl("rect", {
          x: xScale(start),
          y: margin.top,
          width: Math.max(1, xScale(end) - xScale(start)),
          height: plotHeight,
          class: "timeline-sag",
        })
      );
    });

    if (options.threshold && isFiniteNumber(options.threshold.value)) {
      const y = yScale(options.threshold.value);
      svg.appendChild(
        svgEl("line", {
          x1: margin.left,
          x2: width - margin.right,
          y1: y,
          y2: y,
          class: "chart-threshold",
        })
      );
    }

    const d = pathFor(times, values, xScale, yScale);
    svg.appendChild(
      svgEl("path", {
        d,
        class: "chart-path",
        stroke: "#2563eb",
      })
    );

    textEl(svg, margin.left + 2, margin.top + 12, `${options.formatY(yMax)}`, {
      class: "chart-axis-label",
    });
    textEl(svg, margin.left + 2, margin.top + plotHeight - 2, `${options.formatY(yMin)}`, {
      class: "chart-axis-label",
    });
    textEl(svg, margin.left, height - 4, options.formatX(xMin), {
      class: "chart-axis-label",
    });
    textEl(svg, width - margin.right, height - 4, options.formatX(xMax), {
      class: "chart-axis-label",
      "text-anchor": "end",
    });
  }

  window.PowerCharts = { line, sparkline, PALETTE };
})();
