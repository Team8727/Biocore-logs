# Biocore Power Logs

Local implementation of the Biocore power-log pipeline. The robot writes one WPILib `.wpilog`; the driver station uploads it to the `logs` branch; GitHub Actions computes enabled-time energy, sag, and peak metrics; a static site on GitHub Pages presents a newcomer-first **Simple** view and a detailed **Complex** view.

Repository: <https://github.com/Team8727/Biocore-logs>

This workspace is the source for `Team8727/Biocore-logs`. The `logs` branch, GitHub Pages settings, and driver-station credentials are deployment steps.

## Architecture

Robot code on the roboRIO writes typed power samples to the same WPILib `DataLogManager` WPILog used by DogLog: battery voltage, PDH total current, per-subsystem and per-motor supply current, enabled state, and match metadata. After each match, a one-click uploader on the driver-station laptop copies new logs over SSH, compresses them to `.wpilog.xz`, and pushes them to the `logs` branch of the public `Biocore-logs` repo; it deduplicates by SHA-256, keeps an offline retry queue, and never deletes the roboRIO copy. A GitHub Actions workflow on `logs` pushes selects only new files, parses them with a versioned Java tool built on WPILib's `DataLogReader`, computes metrics at the full ~50 Hz log resolution, writes small summary JSON plus 10 Hz series JSON to `main`, and deploys the static site to GitHub Pages. Browsers load only the index and summaries in Simple mode, lazy-load series in Complex mode, and link to the raw compressed log for audit; data-quality flags make missing or low-rate data visible instead of silently interpolating.

## Tech stack and tradeoffs

| Layer | Implemented choice | Why | Tradeoff |
|---|---|---|---|
| Robot logging | WPILib Java + existing DogLog + `PowerDistribution` + TalonFX `getSupplyCurrent()` | DogLog already writes typed entries into the WPILib WPILog; no second logging stack | Robot-side `PowerTelemetry` must be copied into the robot project and PDH CAN ID verified |
| Upload | Python 3 stdlib + OpenSSH `scp` + `git` | Cross-platform, no GitHub API code, easy offline queue | Requires Python and one-time git auth on the DS laptop |
| Processing | GitHub Actions + Java CLI using WPILib `DataLogReader` | One parser version; exact WPILib semantics; unit-tested | About a minute of Action latency; GitHub dependency at events |
| Site | No-build HTML/CSS/ES modules + dependency-free SVG charts | Works with the installed toolchain, loads fast, no build step or CDN | Less component tooling than React; charts are intentionally focused |
| Hosting | GitHub Pages from `main` via Actions | Free for public repos, same repo as data, no server | 1 GB Pages soft limit; no backend |
| Raw storage | Dedicated `logs` branch, `.wpilog.xz` | Keeps laptop clones and Pages artifacts small | GitHub repo size still grows; use retention |
| Derived data | Small `summary.json` + 10 Hz `series.json` + raw link | Simple mode is instant; Complex mode is bounded; full-resolution metrics remain accurate | Chart series are downsampled; raw log is the audit source |

**Deliberate deviation:** the plan recommended Vite + React + ECharts. The local machine has no Node/npm, and the site only needs a handful of views, so the MVP uses a no-build static app with custom SVG charts. This removes a build step and a runtime CDN. If the team later adds Node, the JSON contract and visual design can move to Vite/React without changing the parser or uploader.

**Why DogLog is not a second file:** DogLog's `DataLogWriter` writes into `DataLogManager.getLog()` and prefixes keys with `/Robot`. The single canonical file is the usual WPILib `FRC_*.wpilog`; the parser strips the `/Robot/` prefix.

## MVP features

### Robot-side logging
- `PowerTelemetry` singleton with a stable key schema, called every robot loop.
- Battery voltage, PDH total current/power, and a `PDHAvailable` flag.
- Per-subsystem current and per-motor supply current; the provided integration registers four drive and four steer motors, and future mechanisms use `registerMotor`.
- Enabled/autonomous/teleop/test state, match time, alliance, event name, match number, replay number, match type, robot serial, schema version.
- Optional `DogLog.setPdh(pdh)` for DogLog's built-in PDH extras.

### Driver-station uploader
- One-click script pulls new `.wpilog` files from `/home/lvuser/logs`.
- SHA-256 dedupe against a local state file; `lzma` compression to `.wpilog.xz`.
- Commits to `logs/<YYYY-MM-DD>/<hash>/` on the `logs` branch; never force-pushes.
- Offline queue: failed pushes stay in `.upload-queue/` and retry next run.
- `--dry-run`, `--input-dir`, and `--push-only` modes for testing and recovery.
- Clear console output: pulled / queued / uploaded / skipped / failed.

### GitHub Actions ingestion
- Triggers on push to `logs` and manual dispatch.
- Selects only unprocessed hashes; `data/processed.json` records parser version.
- Decompresses, validates, and computes metrics at full resolution.
- Splits one log into multiple sessions when disabled gaps exceed 30 s or FMS match metadata changes; each session gets its own report.
- Emits `data/index.json`, `data/sessions/<id>.summary.json`, `.series.json`, `.index.json`.
- Commits derived data to `main` and deploys Pages.
- Reprocess everything by bumping the parser version.

### Website
- **Simple mode (default):** six tiles (energy, time enabled, lowest voltage, sag events, peak draw, peak power), a voltage timeline with sag shading, energy-by-subsystem bars, energy-by-motor bars, and plain-language definitions.
- **Complex mode:** voltage/current/power charts, one line per subsystem, subsystem/motor tables, sag-event table, data-quality panel, threshold display, and raw-log download.
- Newcomer UX: units on every value, no unexplained acronyms, colorblind-safe palette, responsive layout, keyboard-accessible controls.
- Accuracy UX: "motor-only" warning when PDH is absent, gap/missing-key warnings, and energy-coverage reporting; gaps are never silently interpolated.
- Deep links: `?session=<id>&mode=complex`; latest session loads by default.

## Repository layout

```text
.
├── index.html                     # static report shell
├── assets/
│   ├── app.js                     # simple/complex rendering, data loading
│   ├── charts.js                  # dependency-free SVG line/sparkline charts
│   └── styles.css
├── config/
│   ├── power-metrics.json         # parse thresholds and required keys
│   └── uploader.example.json      # copy to uploader.json on the DS laptop
├── data/
│   ├── index.json                 # session manifest
│   ├── processed.json             # SHA-256 -> parser version
│   ├── samples/synthetic.wpilog   # demo log for local testing
│   └── sessions/                  # per-session summary/series/index JSON
├── docs/
│   ├── RUNBOOK.md                 # operator instructions
│   └── DATA_SCHEMA.md             # keys, JSON, metric formulas
├── robot/                         # drop-in WPILib Java sources + integration notes
├── tools/
│   ├── upload_logs.py             # one-click DS uploader
│   ├── upload_logs.command/.bat   # wrappers
│   ├── ci_select_logs.py          # selects new logs for the Action
│   └── power-parser/              # Java parser, tests, Gradle wrapper
└── .github/workflows/
    ├── ingest.yml                 # logs branch -> parser -> main -> Pages
    ├── deploy.yml                 # site/data changes -> Pages
    └── deploy-pages.yml           # reusable Pages deployment
```

## Local quick start

The repo already includes a synthetic sample and its derived report.

```bash
# 1. Run parser unit tests (use your WPILib JDK; Windows: %USERPROFILE%\wpilib\2026\jdk)
cd tools/power-parser
JAVA_HOME="$HOME/wpilib/2026/jdk" ./gradlew test installDist

# 2. Regenerate the sample report (optional)
cd ../..
JAVA_HOME="$HOME/wpilib/2026/jdk" \
  tools/power-parser/build/install/power-parser/bin/power-parser \
  --input data/samples/synthetic.wpilog \
  --output-dir data \
  --config config/power-metrics.json

# 3. Preview the site (fetch needs HTTP, not file://)
python3 -m http.server 8765
# open http://localhost:8765
```

Test the uploader without touching GitHub:

```bash
cp config/uploader.example.json config/uploader.json
# edit team number, host, repo URL
python3 tools/upload_logs.py --input-dir data/samples --dry-run
```

Test the full upload path against a local bare repo by pointing `repo_url` and `repo_path` at temporary paths; `docs/RUNBOOK.md` has the exact commands.

## Deploying to GitHub Pages

1. Create a **public** `Biocore-logs` repository.
2. Push this workspace to `main`.
3. Enable Pages: **Settings → Pages → Build and deployment → Source: GitHub Actions**.
4. Create the `logs` branch by running the uploader once, or manually create an orphan branch with a `logs/README.md`.
5. Put `config/uploader.json` on the DS laptop only; add it to `.gitignore` (already ignored).
6. Use a fine-grained PAT or SSH key with write access to `Biocore-logs`; never commit it.
7. Confirm the site URL `https://<org>.github.io/Biocore-logs/` and open it on a second laptop.

If `main` is protected, allow the Actions bot to push (or change the ingest workflow to open a pull request). The parser and site do not require a backend.

The `data/` folder must be deployed with the site. The reusable Pages workflow stages `index.html`, `assets/`, `data/`, and `config/` into `_site` before uploading.

## Metric definitions

| Metric | Definition |
|---|---|
| Energy by subsystem / motor | `Σ (V_battery × I_supply) × Δt` over enabled samples, Wh |
| Time enabled | Sum of intervals where `Power/State/Enabled` is true |
| Lowest voltage | Minimum 100 ms rolling mean over enabled time; instantaneous min is shown in Complex |
| Sag | Voltage < 9.0 V for at least 100 ms; ends after 100 ms above 9.2 V |
| Peak draw / power | Highest 100 ms rolling mean of total current / power; instantaneous peak is shown in Complex |
| Total current source | PDH total when available; otherwise the sum of registered motors, labeled motor-only |
| Unmetered energy | Total energy minus the sum of subsystem energy; static loads when PDH is present |

All thresholds live in `config/power-metrics.json`. Energy is trapezoidal integration over WPILog timestamps; gaps over 200 ms are excluded and reported, not interpolated. Negative supply current is clamped to zero for consumed energy.

## Accuracy and safety

- The parser uses supply current, not stator/torque current, for power budgeting.
- Required keys are `Power/Battery/Voltage` and `Power/State/Enabled`; missing keys produce an incomplete badge instead of fabricated numbers.
- The Action never parses the same SHA-256 twice for the same parser version.
- Raw logs are the audit source; summaries and charts can be regenerated from them.
- No secrets are stored in the repo; push and uploader credentials live in the OS credential manager or SSH agent.

## Known limitations

- The checked-in sample session is demo data, not a real match. Remove it before publishing real logs; see `data/samples/README.md`.
- Non-drivetrain mechanisms are supported by the API but not registered until their code calls `registerMotor`.
- Negative supply current is clamped to zero for consumed energy; regenerative current is not reported separately in the MVP.
- PDH CAN ID/type defaults to `1`/REV; verify with the electrical lead.
- Event/match metadata is blank outside FMS and is labeled as a practice session.
- The site is static; there is no live streaming, authentication, or multi-team tenancy.
- Raw-log retention is manual; keep the current season and archive older logs outside the repo.

## Related docs

- `robot/README.md` — drop-in robot sources and exact integration snippets.
- `docs/RUNBOOK.md` — what to do after each match, offline behavior, and recovery.
- `docs/DATA_SCHEMA.md` — robot keys, JSON schema, and parser contract.
