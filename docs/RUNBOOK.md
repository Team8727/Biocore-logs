# Power Log Runbook

## One-time setup

1. Clone `Biocore-logs` on the driver-station laptop, or copy the `tools/` and `config/` folders to a local working directory. Run the uploader from that directory.
2. Copy `config/uploader.example.json` to `config/uploader.json`.
3. Set `team_number`, `roborio_host` (for example `roborio-9999-frc.local`), and `repo_url`.
4. Authenticate `git` to GitHub once on the driver-station laptop (HTTPS credential manager or SSH key). Do not put a token in `uploader.json`.
5. Connect the laptop to the robot network and run:

   ```bash
   python3 tools/upload_logs.py --dry-run
   ```

   The dry run must find the roboRIO and list queued files without pushing.
6. Deploy this repo to `Biocore-logs` with the site on `main` and raw logs on `logs`. Enable **Settings → Pages → Source: GitHub Actions**.
7. Open the Pages URL on a second laptop and confirm it loads.

## After each match

1. Connect the driver-station laptop to the robot network.
2. Double-click `tools/upload_logs.command` (macOS) or `tools/upload_logs.bat` (Windows).
3. Wait for `Uploaded N new file(s); archived M queued file(s).`
4. The GitHub Action starts automatically. After about a minute, refresh the Pages site; the newest session is selected.
5. If the site still shows the old session, open the Actions tab and check the **Ingest power logs** run.

The uploader never deletes the roboRIO logs. It is safe to run twice; SHA-256 dedupe skips files already uploaded.

## What success looks like

```text
[upload] Pulled 1 file(s).
[upload] FRC_2026...wpilog: queued (48.8 KB -> 4.5 KB)
[upload] Newly queued: 1
[upload] Uploaded 1 new file(s); archived 1 queued file(s).
```

The Action log ends with:

```text
Parsed FRC_2026...wpilog -> <session id> (complete=true, energy=..., enabled=...)
Done: parsed=1 skipped=0 failed=0 index=data/index.json
```

## Offline or failed upload

- If the robot network or GitHub is unavailable, the script keeps files in `.upload-queue/` and exits with a message.
- Do not delete anything from the roboRIO or the queue.
- When connectivity returns, run the uploader again. It retries the queue first.
- To retry only the queue without pulling new logs:

  ```bash
  python3 tools/upload_logs.py --push-only
  ```

- If `scp` fails, verify the roboRIO hostname and that the laptop is on the robot network:

  ```bash
  scp lvuser@roborio-9999-frc.local:/home/lvuser/logs/*.wpilog /tmp/
  ```

- If the push fails, check one-time git authentication and that the `logs` branch exists.
- For a local end-to-end check without GitHub, point `repo_url` and `repo_path` at a temporary bare repo and run the uploader with `--input-dir data/samples`. The local bare repo is only a test fixture; it is not part of the real pipeline.

## Reading Simple mode (newcomers)

1. **Energy used** — total battery energy while the robot was enabled, in Wh. The note says if some energy is unmetered.
2. **Time enabled** — how long the robot was enabled in this session.
3. **Lowest voltage** — the lowest 100 ms average battery voltage. Lower is worse.
4. **Sag events** — how many times voltage dropped below 9.0 V for at least 100 ms.
5. **Peak draw / peak power** — the highest 100 ms average current/power. These are the numbers to compare with breaker and battery limits.
6. **Energy by subsystem / motor** — where the energy went. Longer bars mean more energy.
7. **Match timeline** — the battery voltage line; shaded areas are sags.

If a yellow banner appears, read it before trusting the numbers. It explains missing keys, motor-only totals, data gaps, or low energy coverage.

## Reading Complex mode

- **Charts** — voltage, total current, total power, and one line per subsystem. Hover to inspect values.
- **Subsystem detail** — energy, share, peak/average current, and peak power.
- **Motor detail** — the same metrics per motor, useful for finding a single dragging or overworked motor.
- **Voltage sags** — each episode with start, end, duration, minimum, and depth.
- **Data quality** — sample rate, gaps, PDH availability, current source, coverage, and missing keys.
- **Download raw log** — the compressed `.wpilog.xz` used to produce the report.

## Troubleshooting matrix

| Symptom | Likely cause | Fix |
|---|---|---|
| `Config is missing required keys` | `config/uploader.json` incomplete | Copy the example and fill in team/host/repo |
| `scp failed` | Wrong hostname, robot off, or laptop on the wrong network | Ping the roboRIO, join the robot network, retry |
| `Push failed` | Git auth expired or `logs` branch missing | Re-authenticate git; run the uploader once to create `logs` |
| Site shows no sessions | Action still running, Pages not enabled, or `data/index.json` not deployed | Check Actions, enable Pages with GitHub Actions source, rerun deploy |
| New session missing | Log was already processed or parser skipped it | Check `data/processed.json`; bump parser version to reprocess |
| `Incomplete log` banner | Robot code did not log required keys | Confirm `Robot/Power/Battery/Voltage` and `Robot/Power/State/Enabled` exist |
| `motor-only` banner | PDH absent or wrong CAN ID | Verify `PowerConstants.PDH_CAN_ID` and PDH wiring |
| Voltage values look wrong | Stator current used instead of supply current | Use `getSupplyCurrent()` everywhere |
| Energy is lower than expected | Gaps, disabled time, or missing motors | Check the data-quality panel and energy coverage |

## Retention

- Keep raw logs for the current season on the `logs` branch.
- Keep derived summaries and the site data indefinitely.
- Before a new season, archive or remove older raw logs and commit the change directly on `logs`.
- Monitor the repository and Pages sizes; move raw logs to GitHub Releases if the repo approaches 1 GB.

## Who to contact

- Robot-side key changes: software lead.
- PDH/CAN ID and breaker questions: electrical lead.
- Uploader, parser, or site failures: the author of the current `Biocore-logs` change.
