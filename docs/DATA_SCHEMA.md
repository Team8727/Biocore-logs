# Power Data Schema

## Robot WPILog keys

DogLog writes into the WPILib `DataLogManager` WPILog and prefixes every key with `/Robot`. The canonical key names below appear as `Robot/Power/...` in the file; the parser strips the leading `Robot/` before validation.

| Key | Type | Required | Meaning |
|---|---|---|---|
| `Power/Battery/Voltage` | double | yes | Battery voltage in volts |
| `Power/State/Enabled` | boolean | yes | Robot enabled state |
| `Power/Battery/TotalCurrent` | double | optional | PDH total current in amps |
| `Power/Battery/TotalPower` | double | optional | Logged total power in watts |
| `Power/Battery/PDHAvailable` | boolean | optional | Whether a PDH/PDP total is available |
| `Power/Motors/TotalCurrent` | double | optional | Sum of registered motor supply currents |
| `Power/Subsystem/<name>/Current` | double | optional | Subsystem supply current in amps |
| `Power/Subsystem/<name>/Motor/<motor>/Current` | double | optional | Motor supply current in amps |
| `Power/State/Autonomous` | boolean | optional | Autonomous mode |
| `Power/State/Teleop` | boolean | optional | Teleop mode |
| `Power/State/Test` | boolean | optional | Test mode |
| `Power/State/MatchTime` | double | optional | Driver-station match time |
| `Power/State/Alliance` | string | optional | `Red`, `Blue`, or `Unknown` |
| `Power/Meta/SchemaVersion` | int64 | optional | Robot-side schema version |
| `Power/Meta/TeamNumber` | int64 | optional | Team number |
| `Power/Meta/RobotSerial` | string | optional | Robot serial number |
| `Power/Meta/EventName` | string | optional | FMS event name |
| `Power/Meta/MatchNumber` | int64 | optional | FMS match number |
| `Power/Meta/ReplayNumber` | int64 | optional | FMS replay number |
| `Power/Meta/MatchType` | string | optional | `Practice`, `Qualification`, `Playoff`, etc. |

Fallbacks supported by the parser:

- `Robot/SystemStats/BatteryVoltage` when `Power/Battery/Voltage` is absent.
- `Robot/SystemStats/PowerDistribution/TotalCurrent` when `Power/Battery/TotalCurrent` is absent.
- `Robot/DS/Enabled` when `Power/State/Enabled` is absent.

## Summary JSON

Path: `data/sessions/<session_id>.summary.json`

```json
{
  "schema_version": 1,
  "parser_version": 1,
  "session_id": "b778b2edb92695aa",
  "session_index": 0,
  "session_count": 1,
  "source_file": "FRC_2026....wpilog",
  "sha256": "...",
  "processed_at": "2026-11-17T21:29:16Z",
  "metadata": {
    "event_name": "Test Event",
    "match_number": 42,
    "replay_number": 1,
    "match_type": "Qualification",
    "team_number": 9999,
    "robot_serial": "TEST-ROBOT",
    "schema_version": 1,
    "session_index": 0,
    "session_count": 1,
    "source_file": "synthetic.wpilog",
    "sha256": "..."
  },
  "metrics": {
    "enabled_seconds": 8.0,
    "total_energy_wh": 2.6649,
    "lowest_voltage_v": 8.5,
    "instantaneous_min_voltage_v": 8.5,
    "sag_count": 1,
    "sag_total_seconds": 0.5,
    "sag_worst_depth_v": 0.5,
    "peak_current_a": 200,
    "instantaneous_peak_current_a": 200,
    "peak_power_w": 2500,
    "instantaneous_peak_power_w": 2500,
    "average_current_a": 99.88,
    "average_power_w": 1198.57,
    "unmetered_energy_wh": 0.5330
  },
  "subsystems": [
    {
      "name": "Drivetrain",
      "energy_wh": 1.7322,
      "peak_current_a": 130,
      "average_current_a": 64.92,
      "peak_power_w": 1625,
      "share_pct": 65
    }
  ],
  "motors": [
    {
      "subsystem": "Drivetrain",
      "name": "Drive0",
      "energy_wh": 0.4331,
      "peak_current_a": 32.5,
      "average_current_a": 16.23,
      "peak_power_w": 406.25,
      "share_pct": 25
    }
  ],
  "sags": [
    {
      "start_s": 3.0,
      "end_s": 3.5,
      "duration_s": 0.5,
      "min_v": 8.5,
      "depth_v": 0.5
    }
  ],
  "data_quality": {
    "complete": true,
    "missing_keys": [],
    "sample_rate_hz": 50,
    "gap_count": 0,
    "gap_seconds": 0,
    "voltage_source": "Power/Battery/Voltage",
    "enabled_source": "Power/State/Enabled",
    "pdh_available": true,
    "total_current_source": "pdh",
    "subsystem_count": 3,
    "motor_count": 7,
    "motor_current_coverage": 1,
    "enabled_seconds": 8.0,
    "energy_coverage_seconds": 8.0,
    "energy_missing_seconds": 0,
    "schema_version": 1,
    "parser_version": 1
  },
  "summary_path": "sessions/b778b2edb92695aa.summary.json",
  "series_path": "sessions/b778b2edb92695aa.series.json",
  "raw_url": "https://raw.githubusercontent.com/ORG/Biocore-logs/logs/logs/2026-11-17/b778b2ed/synthetic.wpilog.xz"
}
```

## Series JSON

Path: `data/sessions/<session_id>.series.json`, downsampled to `series_hz` (default 10 Hz).

```json
{
  "schema_version": 1,
  "session_id": "b778b2edb92695aa",
  "sample_rate_hz": 10,
  "time_s": [0, 0.1, 0.2],
  "battery_voltage_v": [12.5, 12.5, 12.5],
  "total_current_a": [0, 0, 0],
  "total_power_w": [0, 0, 0],
  "enabled": [0, 0, 0],
  "subsystem_power_w": {
    "Drivetrain": [0, 0, 0],
    "Intake": [0, 0, 0],
    "Shooter": [0, 0, 0]
  },
  "motor_power_w": {
    "Drivetrain/Drive0": [0, 0, 0]
  }
}
```

Missing or invalid samples are written as JSON `null`; charts break the line instead of drawing through a gap. `time_s` is relative to the first sample in the session.

## Index JSON

Path: `data/index.json`

```json
{
  "schema_version": 1,
  "generated_at": "2026-11-17T21:29:16Z",
  "sessions": [
    {
      "session_id": "b778b2edb92695aa",
      "session_index": 0,
      "session_count": 1,
      "source_file": "FRC_2026....wpilog",
      "processed_at": "2026-11-17T21:29:16Z",
      "metadata": {},
      "headline": {
        "enabled_seconds": 8.0,
        "total_energy_wh": 2.6649,
        "lowest_voltage_v": 8.5,
        "sag_count": 1,
        "peak_current_a": 200,
        "peak_power_w": 2500
      },
      "summary_path": "sessions/b778b2edb92695aa.summary.json",
      "series_path": "sessions/b778b2edb92695aa.series.json",
      "complete": true,
      "raw_url": "..."
    }
  ]
}
```

Sessions are sorted newest-processed first. The site uses `headline` for the selector and tiles, then fetches the summary.

## Processed store

Path: `data/processed.json`

```json
{
  "version": 1,
  "parser_version": 1,
  "logs": {
    "<sha256 of raw .wpilog>": {
      "source_file": "FRC_2026....wpilog",
      "session_id": "b778b2edb92695aa",
      "processed_at": "2026-11-17T21:29:16Z",
      "parser_version": 1
    }
  }
}
```

- The hash is of the **decompressed** `.wpilog`, not the `.xz` upload.
- `parser_version` at the root gates reprocessing: if it differs, the selector treats all logs as new.
- `--skip-processed` makes the parser idempotent.

## Session splitting

A single `.wpilog` may contain more than one match because the robot program can stay running across matches. The parser groups enabled intervals into sessions:

- Enabled intervals separated by more than `session_merge_gap_s` (default 30 s) become separate sessions.
- A change in `Power/Meta/MatchNumber`, `ReplayNumber`, `EventName`, or `MatchType` also starts a new session.
- Autonomous and teleop usually stay in one session because the disabled gap between them is short.
- One session uses `session_id = sha256[0:16]`; multiple sessions use `sha256[0:12]-s<index>`.
- Each summary includes `session_index` and `session_count`; the site labels multi-part logs.

## Metric rules

| Rule | Implementation |
|---|---|
| Enabled-only integration | A sample interval contributes only when `Power/State/Enabled` is true at its midpoint |
| Energy | Trapezoidal `∫ V × I dt`; `I` is clamped to `max(0, I)` for consumed energy |
| Time enabled | Sum of enabled boolean intervals from the raw log |
| Lowest voltage | Minimum 100 ms rolling mean over enabled samples; instantaneous min is separate |
| Sag | Start below `sag_threshold_v`; end above `sag_recover_v`; require `sag_min_ms` |
| Peak current/power | Maximum 100 ms rolling mean over enabled samples; instantaneous peak is separate |
| Gaps | Intervals longer than `gap_ms` are excluded from energy and reported in data quality |
| Motor-only total | Used when PDH is absent/zero; the site shows a motor-only banner |
| Unmetered energy | `max(0, total_energy_wh - Σ subsystem energy_wh)` |

Thresholds are in `config/power-metrics.json`:

```json
{
  "sag_threshold_v": 9.0,
  "sag_recover_v": 9.2,
  "sag_min_ms": 100,
  "peak_window_ms": 100,
  "min_voltage_window_ms": 100,
  "gap_ms": 200,
  "grid_hz": 50,
  "series_hz": 10,
  "session_merge_gap_s": 30,
  "clamp_negative_current": true,
  "parser_version": 1,
  "required_keys": ["Power/Battery/Voltage", "Power/State/Enabled"]
}
```

## Versioning

- `schema_version`: the JSON contract. Bump when a field changes meaning or is removed.
- `parser_version`: the metric implementation. Bump when formulas, thresholds, or sampling change. Update `config/power-metrics.json`, `--parser-version` in `.github/workflows/ingest.yml`, and the `PARSER_VERSION` default in `tools/ci_select_logs.py`; the next ingest reprocesses all logs.
- `Power/Meta/SchemaVersion`: the robot-side key schema. Bump when robot keys change.

The site is tolerant of extra fields and shows `—`/null for missing optional values.
