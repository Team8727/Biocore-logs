package org.biocore.power;

import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a WPILib WPILog into power summaries and chart series. */
public final class PowerLogParser {
    public static final int SCHEMA_VERSION = 1;
    public static final int PARSER_VERSION = 1;

    private static final Pattern SUBSYSTEM_PATTERN =
            Pattern.compile("^Power/Subsystem/([^/]+)/Current$");
    private static final Pattern MOTOR_PATTERN =
            Pattern.compile("^Power/Subsystem/([^/]+)/Motor/([^/]+)/Current$");

    private final MetricsConfig config;

    public PowerLogParser(MetricsConfig config) {
        this.config = config;
    }

    public List<ParseResult> parse(Path logPath, String rawUrl) throws IOException {
        byte[] bytes = Files.readAllBytes(logPath);
        String sha256 = sha256(bytes);
        LogData data = readLog(bytes);
        return parseData(data, logPath.getFileName().toString(), sha256, rawUrl);
    }

    public static String fileSha256(Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
    }

    private List<ParseResult> parseData(
            LogData data, String sourceFile, String sha256, String rawUrl) {
        List<SessionRange> sessions = findSessions(data);
        List<ParseResult> results = new ArrayList<>();
        String processedAt = Instant.now().toString();
        for (int index = 0; index < sessions.size(); index++) {
            SessionRange session = sessions.get(index);
            results.add(
                    parseSession(
                            data,
                            sourceFile,
                            sha256,
                            rawUrl,
                            session.start,
                            session.end,
                            index,
                            sessions.size(),
                            processedAt));
        }
        return results;
    }

    private ParseResult parseSession(
            LogData data,
            String sourceFile,
            String sha256,
            String rawUrl,
            double start,
            double end,
            int sessionIndex,
            int sessionCount,
            String processedAt) {
        List<Sample> voltageSamples = firstSeries(data.doubles, "Power/Battery/Voltage");
        String voltageSource = "Power/Battery/Voltage";
        if (voltageSamples.isEmpty()) {
            voltageSamples = firstSeries(data.doubles, "SystemStats/BatteryVoltage");
            voltageSource = "SystemStats/BatteryVoltage";
        }

        List<BoolSample> enabledSamples = firstBoolSeries(data.booleans, "Power/State/Enabled");
        String enabledSource = "Power/State/Enabled";
        if (enabledSamples.isEmpty()) {
            enabledSamples = firstBoolSeries(data.booleans, "DS/Enabled");
            enabledSource = "DS/Enabled";
        }

        Map<String, List<Sample>> subsystemSeries = new LinkedHashMap<>();
        Map<String, List<Sample>> motorSeries = new LinkedHashMap<>();
        for (String key : data.doubles.keySet()) {
            Matcher subsystemMatcher = SUBSYSTEM_PATTERN.matcher(key);
            if (subsystemMatcher.matches()) {
                subsystemSeries.put(subsystemMatcher.group(1), data.doubles.get(key));
                continue;
            }
            Matcher motorMatcher = MOTOR_PATTERN.matcher(key);
            if (motorMatcher.matches()) {
                motorSeries.put(
                        motorMatcher.group(1) + "/" + motorMatcher.group(2), data.doubles.get(key));
            }
        }

        Set<String> subsystemNames = new LinkedHashSet<>(subsystemSeries.keySet());
        for (String motorKey : motorSeries.keySet()) {
            subsystemNames.add(motorKey.substring(0, motorKey.indexOf('/')));
        }

        List<Sample> pdhCurrent = firstSeries(data.doubles, "Power/Battery/TotalCurrent");
        List<Sample> motorTotal = firstSeries(data.doubles, "Power/Motors/TotalCurrent");
        List<Sample> pdhSystemCurrent =
                firstSeries(data.doubles, "SystemStats/PowerDistribution/TotalCurrent");
        boolean pdhAvailable = lastBoolean(data.booleans, "Power/Battery/PDHAvailable", false)
                || !pdhSystemCurrent.isEmpty()
                || (hasPositive(pdhCurrent));

        String totalCurrentSource;
        List<Sample> totalCurrentSamples;
        if (pdhAvailable && hasPositive(pdhCurrent)) {
            totalCurrentSamples = pdhCurrent;
            totalCurrentSource = "pdh";
        } else if (hasPositive(motorTotal)) {
            totalCurrentSamples = motorTotal;
            totalCurrentSource = "motor_total";
        } else if (!motorSeries.isEmpty()) {
            totalCurrentSamples = null;
            totalCurrentSource = "motor_sum";
        } else if (hasPositive(pdhCurrent)) {
            totalCurrentSamples = pdhCurrent;
            totalCurrentSource = "pdh";
        } else {
            totalCurrentSamples = pdhCurrent;
            totalCurrentSource = "missing";
        }

        int gridSize = Math.max(2, (int) Math.round((end - start) * config.gridHz) + 1);
        double[] times = new double[gridSize];
        for (int i = 0; i < gridSize; i++) {
            times[i] = start + i / config.gridHz;
        }

        double[] voltage = fillValue(voltageSamples, times);
        boolean[] enabled = new boolean[gridSize];
        for (int i = 0; i < gridSize; i++) {
            enabled[i] = holdBoolean(enabledSamples, times[i]);
        }

        double[] totalCurrent = new double[gridSize];
        if (totalCurrentSamples != null) {
            totalCurrent = fillCurrent(totalCurrentSamples, times);
        } else {
            totalCurrent = sumMotorSeries(motorSeries, times);
        }

        Map<String, double[]> subsystemCurrent = new LinkedHashMap<>();
        for (String subsystem : subsystemNames) {
            List<Sample> series = subsystemSeries.get(subsystem);
            if (series != null && !series.isEmpty()) {
                subsystemCurrent.put(subsystem, fillCurrent(series, times));
            } else {
                Map<String, List<Sample>> motorsForSubsystem = new LinkedHashMap<>();
                for (Map.Entry<String, List<Sample>> entry : motorSeries.entrySet()) {
                    if (entry.getKey().startsWith(subsystem + "/")) {
                        motorsForSubsystem.put(entry.getKey(), entry.getValue());
                    }
                }
                subsystemCurrent.put(subsystem, sumMotorSeries(motorsForSubsystem, times));
            }
        }

        Map<String, double[]> motorCurrent = new LinkedHashMap<>();
        for (Map.Entry<String, List<Sample>> entry : motorSeries.entrySet()) {
            motorCurrent.put(entry.getKey(), fillCurrent(entry.getValue(), times));
        }

        double[] totalPower = power(voltage, totalCurrent);
        Map<String, double[]> subsystemPower = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : subsystemCurrent.entrySet()) {
            subsystemPower.put(entry.getKey(), power(voltage, entry.getValue()));
        }
        Map<String, double[]> motorPower = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : motorCurrent.entrySet()) {
            motorPower.put(entry.getKey(), power(voltage, entry.getValue()));
        }

        double timeEnabled = timeEnabled(enabledSamples, start, end);
        double totalEnergyJ = integratePower(times, enabled, enabledSamples, totalPower);
        double energyCoverage = integrateCoverage(times, enabled, enabledSamples, totalPower);

        Map<String, Double> subsystemEnergyJ = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : subsystemPower.entrySet()) {
            subsystemEnergyJ.put(
                    entry.getKey(), integratePower(times, enabled, enabledSamples, entry.getValue()));
        }

        Map<String, Double> motorEnergyJ = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : motorPower.entrySet()) {
            motorEnergyJ.put(
                    entry.getKey(), integratePower(times, enabled, enabledSamples, entry.getValue()));
        }

        int minVoltageWindow = windowSamples(config.minVoltageWindowSeconds);
        int peakWindow = windowSamples(config.peakWindowSeconds);
        double lowestVoltage = minRollingMean(voltage, enabled, minVoltageWindow);
        double instantaneousMinVoltage = minValue(voltage, enabled);
        double peakCurrent = maxRollingMean(totalCurrent, enabled, peakWindow);
        double instantaneousPeakCurrent = maxValue(totalCurrent, enabled);
        double peakPower = maxRollingMean(totalPower, enabled, peakWindow);
        double instantaneousPeakPower = maxValue(totalPower, enabled);
        double averageCurrent = mean(totalCurrent, enabled);
        double averagePower = mean(totalPower, enabled);

        List<Map<String, Object>> sags = detectSags(times, voltage, enabled, start);
        double sagTotalSeconds = 0.0;
        double sagWorstDepth = 0.0;
        for (Map<String, Object> sag : sags) {
            sagTotalSeconds += ((Number) sag.get("duration_s")).doubleValue();
            sagWorstDepth = Math.max(sagWorstDepth, ((Number) sag.get("depth_v")).doubleValue());
        }

        Map<String, Object> metadata = metadata(data, sourceFile, sha256, start);
        metadata.put("session_index", sessionIndex);
        metadata.put("session_count", sessionCount);
        Map<String, Object> dataQuality =
                dataQuality(
                        data,
                        voltageSource,
                        enabledSource,
                        totalCurrentSource,
                        pdhAvailable,
                        subsystemNames,
                        motorSeries,
                        times,
                        enabled,
                        motorCurrent,
                        timeEnabled,
                        energyCoverage,
                        start,
                        end);

        double totalEnergyWh = totalEnergyJ / 3600.0;
        double subsystemEnergyWh = subsystemEnergyJ.values().stream().mapToDouble(v -> v / 3600.0).sum();
        double unmeteredEnergyWh = Math.max(0.0, totalEnergyWh - subsystemEnergyWh);

        List<Map<String, Object>> subsystems = new ArrayList<>();
        for (Map.Entry<String, Double> entry : subsystemEnergyJ.entrySet()) {
            double[] current = subsystemCurrent.get(entry.getKey());
            double[] power = subsystemPower.get(entry.getKey());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getKey());
            item.put("energy_wh", entry.getValue() / 3600.0);
            item.put("peak_current_a", maxRollingMean(current, enabled, peakWindow));
            item.put("average_current_a", mean(current, enabled));
            item.put("peak_power_w", maxRollingMean(power, enabled, peakWindow));
            item.put(
                    "share_pct",
                    totalEnergyWh > 1e-9
                            ? 100.0 * (entry.getValue() / 3600.0) / totalEnergyWh
                            : 0.0);
            subsystems.add(item);
        }
        subsystems.sort(Comparator.comparingDouble(item -> -((Number) item.get("energy_wh")).doubleValue()));

        List<Map<String, Object>> motors = new ArrayList<>();
        for (Map.Entry<String, Double> entry : motorEnergyJ.entrySet()) {
            String key = entry.getKey();
            String subsystem = key.substring(0, key.indexOf('/'));
            String motor = key.substring(key.indexOf('/') + 1);
            double[] current = motorCurrent.get(key);
            double[] power = motorPower.get(key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subsystem", subsystem);
            item.put("name", motor);
            item.put("energy_wh", entry.getValue() / 3600.0);
            item.put("peak_current_a", maxRollingMean(current, enabled, peakWindow));
            item.put("average_current_a", mean(current, enabled));
            item.put("peak_power_w", maxRollingMean(power, enabled, peakWindow));
            double subsystemWh = subsystemEnergyJ.getOrDefault(subsystem, 0.0) / 3600.0;
            item.put(
                    "share_pct",
                    subsystemWh > 1e-9 ? 100.0 * (entry.getValue() / 3600.0) / subsystemWh : 0.0);
            motors.add(item);
        }
        motors.sort(Comparator.comparingDouble(item -> -((Number) item.get("energy_wh")).doubleValue()));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("enabled_seconds", timeEnabled);
        metrics.put("total_energy_wh", totalEnergyWh);
        metrics.put("lowest_voltage_v", lowestVoltage);
        metrics.put("instantaneous_min_voltage_v", instantaneousMinVoltage);
        metrics.put("sag_count", sags.size());
        metrics.put("sag_total_seconds", sagTotalSeconds);
        metrics.put("sag_worst_depth_v", sagWorstDepth);
        metrics.put("peak_current_a", peakCurrent);
        metrics.put("instantaneous_peak_current_a", instantaneousPeakCurrent);
        metrics.put("peak_power_w", peakPower);
        metrics.put("instantaneous_peak_power_w", instantaneousPeakPower);
        metrics.put("average_current_a", averageCurrent);
        metrics.put("average_power_w", averagePower);
        metrics.put("unmetered_energy_wh", unmeteredEnergyWh);

        String sessionId = sessionCount == 1
                ? sha256.substring(0, 16)
                : sha256.substring(0, 12) + "-s" + sessionIndex;
        String summaryPath = "sessions/" + sessionId + ".summary.json";
        String seriesPath = "sessions/" + sessionId + ".series.json";

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema_version", SCHEMA_VERSION);
        summary.put("parser_version", config.parserVersion);
        summary.put("session_id", sessionId);
        summary.put("session_index", sessionIndex);
        summary.put("session_count", sessionCount);
        summary.put("source_file", sourceFile);
        summary.put("sha256", sha256);
        summary.put("processed_at", processedAt);
        summary.put("metadata", metadata);
        summary.put("metrics", metrics);
        summary.put("subsystems", subsystems);
        summary.put("motors", motors);
        summary.put("sags", sags);
        summary.put("data_quality", dataQuality);
        summary.put("summary_path", summaryPath);
        summary.put("series_path", seriesPath);
        if (rawUrl != null && !rawUrl.isBlank()) {
            summary.put("raw_url", rawUrl);
        }

        Map<String, Object> series = buildSeries(
                sessionId, times, start, voltage, totalCurrent, totalPower, enabled, subsystemPower, motorPower);

        Map<String, Object> indexEntry = new LinkedHashMap<>();
        indexEntry.put("session_id", sessionId);
        indexEntry.put("session_index", sessionIndex);
        indexEntry.put("session_count", sessionCount);
        indexEntry.put("source_file", sourceFile);
        indexEntry.put("processed_at", processedAt);
        indexEntry.put("metadata", metadata);
        indexEntry.put(
                "headline",
                Map.of(
                        "enabled_seconds", timeEnabled,
                        "total_energy_wh", totalEnergyWh,
                        "lowest_voltage_v", lowestVoltage,
                        "sag_count", sags.size(),
                        "peak_current_a", peakCurrent,
                        "peak_power_w", peakPower));
        indexEntry.put("summary_path", summaryPath);
        indexEntry.put("series_path", seriesPath);
        indexEntry.put("complete", dataQuality.get("complete"));
        if (rawUrl != null && !rawUrl.isBlank()) {
            indexEntry.put("raw_url", rawUrl);
        }

        return new ParseResult(summary, series, indexEntry, Boolean.TRUE.equals(dataQuality.get("complete")));
    }

    private List<SessionRange> findSessions(LogData data) {
        List<BoolSample> enabled = firstBoolSeries(data.booleans, "Power/State/Enabled");
        if (enabled.isEmpty()) {
            enabled = firstBoolSeries(data.booleans, "DS/Enabled");
        }

        double logStart = dataStart(data);
        double logEnd = dataEnd(data);
        if (!Double.isFinite(logStart) || !Double.isFinite(logEnd) || logEnd <= logStart) {
            return List.of(new SessionRange(0.0, 1.0));
        }
        if (enabled.isEmpty()) {
            return List.of(new SessionRange(logStart, logEnd));
        }

        List<double[]> intervals = new ArrayList<>();
        for (int i = 0; i < enabled.size(); i++) {
            if (!enabled.get(i).value) {
                continue;
            }
            double start = enabled.get(i).time;
            double end;
            if (i + 1 < enabled.size()) {
                end = enabled.get(i + 1).value
                        ? enabled.get(i + 1).time
                        : enabled.get(i).time;
            } else {
                end = logEnd;
            }
            if (end > start) {
                intervals.add(new double[] {start, end});
            }
        }
        if (intervals.isEmpty()) {
            return List.of(new SessionRange(logStart, logEnd));
        }

        List<SessionRange> sessions = new ArrayList<>();
        double groupStart = intervals.get(0)[0];
        double groupEnd = intervals.get(0)[1];
        String groupKey = matchKeyAt(data, groupStart);

        for (int i = 1; i < intervals.size(); i++) {
            double start = intervals.get(i)[0];
            double end = intervals.get(i)[1];
            String key = matchKeyAt(data, start);
            boolean gapSplit = start - groupEnd > config.sessionMergeGapSeconds;
            boolean keySplit = !key.equals(groupKey);
            if (gapSplit || keySplit) {
                sessions.add(new SessionRange(groupStart, groupEnd));
                groupStart = start;
                groupEnd = end;
                groupKey = key;
            } else {
                groupEnd = end;
            }
        }
        sessions.add(new SessionRange(groupStart, groupEnd));
        return sessions;
    }

    private static String matchKeyAt(LogData data, double time) {
        String event = lastStringBefore(data.strings, "Power/Meta/EventName", time, "Practice");
        String type = lastStringBefore(data.strings, "Power/Meta/MatchType", time, "Practice");
        long match = lastLongBefore(data.integers, "Power/Meta/MatchNumber", time, 0L);
        long replay = lastLongBefore(data.integers, "Power/Meta/ReplayNumber", time, 0L);
        return event + "|" + type + "|" + match + "|" + replay;
    }

    private static double dataStart(LogData data) {
        double min = Double.POSITIVE_INFINITY;
        for (List<Sample> samples : data.doubles.values()) {
            if (!samples.isEmpty()) {
                min = Math.min(min, samples.get(0).time);
            }
        }
        for (List<BoolSample> samples : data.booleans.values()) {
            if (!samples.isEmpty()) {
                min = Math.min(min, samples.get(0).time);
            }
        }
        for (List<LongSample> samples : data.integers.values()) {
            if (!samples.isEmpty()) {
                min = Math.min(min, samples.get(0).time);
            }
        }
        for (List<StringSample> samples : data.strings.values()) {
            if (!samples.isEmpty()) {
                min = Math.min(min, samples.get(0).time);
            }
        }
        return min;
    }

    private static double dataEnd(LogData data) {
        double max = Double.NEGATIVE_INFINITY;
        for (List<Sample> samples : data.doubles.values()) {
            if (!samples.isEmpty()) {
                max = Math.max(max, samples.get(samples.size() - 1).time);
            }
        }
        for (List<BoolSample> samples : data.booleans.values()) {
            if (!samples.isEmpty()) {
                max = Math.max(max, samples.get(samples.size() - 1).time);
            }
        }
        for (List<LongSample> samples : data.integers.values()) {
            if (!samples.isEmpty()) {
                max = Math.max(max, samples.get(samples.size() - 1).time);
            }
        }
        for (List<StringSample> samples : data.strings.values()) {
            if (!samples.isEmpty()) {
                max = Math.max(max, samples.get(samples.size() - 1).time);
            }
        }
        return max;
    }

    private static long lastLongBefore(
            Map<String, List<LongSample>> map, String key, double time, long fallback) {
        List<LongSample> samples = map.get(key);
        if (samples == null) {
            return fallback;
        }
        long value = fallback;
        for (LongSample sample : samples) {
            if (sample.time <= time) {
                value = sample.value;
            } else {
                break;
            }
        }
        return value;
    }

    private static String lastStringBefore(
            Map<String, List<StringSample>> map, String key, double time, String fallback) {
        List<StringSample> samples = map.get(key);
        if (samples == null) {
            return fallback;
        }
        String value = fallback;
        for (StringSample sample : samples) {
            if (sample.time <= time) {
                value = sample.value;
            } else {
                break;
            }
        }
        return value;
    }

    private static final class SessionRange {
        final double start;
        final double end;

        SessionRange(double start, double end) {
            this.start = start;
            this.end = end;
        }
    }

    private LogData readLog(byte[] bytes) throws IOException {
        DataLogReader reader = new DataLogReader(ByteBuffer.wrap(bytes));
        if (!reader.isValid()) {
            throw new IOException("Not a valid WPILog file");
        }

        LogData data = new LogData();
        Map<Integer, EntryInfo> entries = new HashMap<>();

        for (DataLogRecord record : reader) {
            if (record.isStart()) {
                DataLogRecord.StartRecordData start = record.getStartData();
                entries.put(start.entry, new EntryInfo(normalize(start.name), start.type));
                continue;
            }
            if (record.isControl()) {
                continue;
            }
            EntryInfo info = entries.get(record.getEntry());
            if (info == null) {
                continue;
            }
            double time = record.getTimestamp() / 1_000_000.0;
            switch (info.type) {
                case "double":
                    data.doubles.computeIfAbsent(info.name, key -> new ArrayList<>())
                            .add(new Sample(time, record.getDouble()));
                    break;
                case "float":
                    data.doubles.computeIfAbsent(info.name, key -> new ArrayList<>())
                            .add(new Sample(time, record.getFloat()));
                    break;
                case "int64":
                    data.integers.computeIfAbsent(info.name, key -> new ArrayList<>())
                            .add(new LongSample(time, record.getInteger()));
                    break;
                case "boolean":
                    data.booleans.computeIfAbsent(info.name, key -> new ArrayList<>())
                            .add(new BoolSample(time, record.getBoolean()));
                    break;
                case "string":
                    data.strings.computeIfAbsent(info.name, key -> new ArrayList<>())
                            .add(new StringSample(time, record.getString()));
                    break;
                default:
                    break;
            }
        }

        for (List<Sample> list : data.doubles.values()) {
            list.sort(Comparator.comparingDouble(sample -> sample.time));
        }
        for (List<LongSample> list : data.integers.values()) {
            list.sort(Comparator.comparingDouble(sample -> sample.time));
        }
        for (List<BoolSample> list : data.booleans.values()) {
            list.sort(Comparator.comparingDouble(sample -> sample.time));
        }
        for (List<StringSample> list : data.strings.values()) {
            list.sort(Comparator.comparingDouble(sample -> sample.time));
        }
        return data;
    }

    private Map<String, Object> metadata(
            LogData data, String sourceFile, String sha256, double time) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(
                "event_name",
                defaultString(
                        lastStringBefore(data.strings, "Power/Meta/EventName", time, null),
                        "Practice"));
        metadata.put(
                "match_number",
                lastLongBefore(data.integers, "Power/Meta/MatchNumber", time, 0L));
        metadata.put(
                "replay_number",
                lastLongBefore(data.integers, "Power/Meta/ReplayNumber", time, 0L));
        metadata.put(
                "match_type",
                defaultString(
                        lastStringBefore(data.strings, "Power/Meta/MatchType", time, null),
                        "Practice"));
        metadata.put(
                "team_number",
                lastLongBefore(data.integers, "Power/Meta/TeamNumber", time, 0L));
        metadata.put(
                "robot_serial",
                defaultString(
                        lastStringBefore(data.strings, "Power/Meta/RobotSerial", time, null), ""));
        metadata.put(
                "schema_version",
                lastLongBefore(data.integers, "Power/Meta/SchemaVersion", time, 0L));
        metadata.put("source_file", sourceFile);
        metadata.put("sha256", sha256);
        return metadata;
    }

    private Map<String, Object> dataQuality(
            LogData data,
            String voltageSource,
            String enabledSource,
            String totalCurrentSource,
            boolean pdhAvailable,
            Set<String> subsystemNames,
            Map<String, List<Sample>> motorSeries,
            double[] times,
            boolean[] enabled,
            Map<String, double[]> motorCurrent,
            double timeEnabled,
            double energyCoverage,
            double start,
            double end) {
        List<String> missing = new ArrayList<>();
        for (String key : config.requiredKeys) {
            if (!data.hasKey(key)) {
                missing.add(key);
            }
        }
        if (firstSeries(data.doubles, "Power/Battery/Voltage").isEmpty()
                && firstSeries(data.doubles, "SystemStats/BatteryVoltage").isEmpty()) {
            addMissing(missing, "Power/Battery/Voltage");
        }
        if (firstBoolSeries(data.booleans, "Power/State/Enabled").isEmpty()
                && firstBoolSeries(data.booleans, "DS/Enabled").isEmpty()) {
            addMissing(missing, "Power/State/Enabled");
        }
        if (!firstSeries(data.doubles, "SystemStats/BatteryVoltage").isEmpty()) {
            missing.remove("Power/Battery/Voltage");
        }
        if (!firstBoolSeries(data.booleans, "DS/Enabled").isEmpty()) {
            missing.remove("Power/State/Enabled");
        }
        if ("missing".equals(totalCurrentSource)) {
            addMissing(missing, "Power/Battery/TotalCurrent");
        }

        double medianDt = medianDelta(firstSeries(data.doubles, "Power/Battery/Voltage"));
        if (!Double.isFinite(medianDt) || medianDt <= 0) {
            medianDt = medianDelta(firstSeries(data.doubles, "SystemStats/BatteryVoltage"));
        }
        double sampleRate = medianDt > 0 ? 1.0 / medianDt : 0.0;

        int gapCount = 0;
        double gapSeconds = 0.0;
        List<Sample> voltageSamples = firstSeries(data.doubles, "Power/Battery/Voltage");
        if (voltageSamples.isEmpty()) {
            voltageSamples = firstSeries(data.doubles, "SystemStats/BatteryVoltage");
        }
        for (int i = 1; i < voltageSamples.size(); i++) {
            double previous = voltageSamples.get(i - 1).time;
            double current = voltageSamples.get(i).time;
            if (current < start || previous > end) {
                continue;
            }
            double dt = current - previous;
            if (dt > config.gapSeconds) {
                gapCount++;
                gapSeconds += dt;
            }
        }

        int motorCoveragePoints = 0;
        int enabledPoints = 0;
        for (int i = 0; i < times.length; i++) {
            if (!enabled[i]) {
                continue;
            }
            enabledPoints++;
            for (double[] current : motorCurrent.values()) {
                if (i < current.length && Double.isFinite(current[i])) {
                    motorCoveragePoints++;
                    break;
                }
            }
        }
        double motorCoverage = enabledPoints > 0 ? (double) motorCoveragePoints / enabledPoints : 0.0;

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("complete", missing.isEmpty() && !voltageSamples.isEmpty());
        quality.put("missing_keys", missing);
        quality.put("sample_rate_hz", sampleRate);
        quality.put("gap_count", gapCount);
        quality.put("gap_seconds", gapSeconds);
        quality.put("voltage_source", voltageSource);
        quality.put("enabled_source", enabledSource);
        quality.put("pdh_available", pdhAvailable);
        quality.put("total_current_source", totalCurrentSource);
        quality.put("subsystem_count", subsystemNames.size());
        quality.put("motor_count", motorSeries.size());
        quality.put("motor_current_coverage", motorCoverage);
        quality.put("enabled_seconds", timeEnabled);
        quality.put("energy_coverage_seconds", energyCoverage);
        quality.put("energy_missing_seconds", Math.max(0.0, timeEnabled - energyCoverage));
        quality.put("schema_version", lastLong(data.integers, "Power/Meta/SchemaVersion", 0L));
        quality.put("parser_version", config.parserVersion);
        return quality;
    }

    private Map<String, Object> buildSeries(
            String sessionId,
            double[] times,
            double start,
            double[] voltage,
            double[] totalCurrent,
            double[] totalPower,
            boolean[] enabled,
            Map<String, double[]> subsystemPower,
            Map<String, double[]> motorPower) {
        int step = Math.max(1, (int) Math.round(config.gridHz / Math.max(1.0, config.seriesHz)));
        List<Double> time = new ArrayList<>();
        List<Double> voltageOut = new ArrayList<>();
        List<Double> currentOut = new ArrayList<>();
        List<Double> powerOut = new ArrayList<>();
        List<Double> enabledOut = new ArrayList<>();
        Map<String, List<Double>> subsystemOut = new LinkedHashMap<>();
        Map<String, List<Double>> motorOut = new LinkedHashMap<>();

        for (String name : subsystemPower.keySet()) {
            subsystemOut.put(name, new ArrayList<>());
        }
        for (String name : motorPower.keySet()) {
            motorOut.put(name, new ArrayList<>());
        }

        for (int i = 0; i < times.length; i += step) {
            time.add(times[i] - start);
            voltageOut.add(voltage[i]);
            currentOut.add(totalCurrent[i]);
            powerOut.add(totalPower[i]);
            enabledOut.add(enabled[i] ? 1.0 : 0.0);
            for (String name : subsystemPower.keySet()) {
                subsystemOut.get(name).add(subsystemPower.get(name)[i]);
            }
            for (String name : motorPower.keySet()) {
                motorOut.get(name).add(motorPower.get(name)[i]);
            }
        }
        int last = times.length - 1;
        if ((times.length - 1) % step != 0) {
            time.add(times[last] - start);
            voltageOut.add(voltage[last]);
            currentOut.add(totalCurrent[last]);
            powerOut.add(totalPower[last]);
            enabledOut.add(enabled[last] ? 1.0 : 0.0);
            for (String name : subsystemPower.keySet()) {
                subsystemOut.get(name).add(subsystemPower.get(name)[last]);
            }
            for (String name : motorPower.keySet()) {
                motorOut.get(name).add(motorPower.get(name)[last]);
            }
        }

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("schema_version", SCHEMA_VERSION);
        series.put("session_id", sessionId);
        series.put("sample_rate_hz", config.seriesHz);
        series.put("time_s", time);
        series.put("battery_voltage_v", voltageOut);
        series.put("total_current_a", currentOut);
        series.put("total_power_w", powerOut);
        series.put("enabled", enabledOut);
        series.put("subsystem_power_w", subsystemOut);
        series.put("motor_power_w", motorOut);
        return series;
    }

    private List<Map<String, Object>> detectSags(
            double[] times, double[] voltage, boolean[] enabled, double start) {
        List<Map<String, Object>> sags = new ArrayList<>();
        boolean inSag = false;
        double eventStart = 0.0;
        double eventMin = Double.POSITIVE_INFINITY;

        for (int i = 0; i < times.length; i++) {
            if (!enabled[i] || !Double.isFinite(voltage[i])) {
                if (inSag) {
                    addSag(sags, eventStart, times[i], eventMin, start);
                    inSag = false;
                }
                continue;
            }
            double v = voltage[i];
            if (!inSag && v < config.sagThresholdV) {
                inSag = true;
                eventStart = times[i];
                eventMin = v;
            } else if (inSag) {
                eventMin = Math.min(eventMin, v);
                if (v > config.sagRecoverV) {
                    addSag(sags, eventStart, times[i], eventMin, start);
                    inSag = false;
                }
            }
        }
        if (inSag) {
            addSag(sags, eventStart, times[times.length - 1], eventMin, start);
        }
        return sags;
    }

    private void addSag(
            List<Map<String, Object>> sags, double startTime, double endTime, double minV, double logStart) {
        double duration = endTime - startTime;
        if (duration < config.sagMinSeconds) {
            return;
        }
        Map<String, Object> sag = new LinkedHashMap<>();
        sag.put("start_s", startTime - logStart);
        sag.put("end_s", endTime - logStart);
        sag.put("duration_s", duration);
        sag.put("min_v", minV);
        sag.put("depth_v", config.sagThresholdV - minV);
        sags.add(sag);
    }

    private double[] sumMotorSeries(Map<String, List<Sample>> series, double[] times) {
        double[] result = new double[times.length];
        for (int i = 0; i < times.length; i++) {
            double sum = 0.0;
            boolean any = false;
            for (List<Sample> samples : series.values()) {
                double value = interpolate(samples, times[i], config.gapSeconds);
                if (Double.isFinite(value)) {
                    sum += config.clampNegativeCurrent ? Math.max(0.0, value) : value;
                    any = true;
                }
            }
            result[i] = any ? sum : Double.NaN;
        }
        return result;
    }

    private double[] fillValue(List<Sample> samples, double[] times) {
        double[] result = new double[times.length];
        for (int i = 0; i < times.length; i++) {
            result[i] = interpolate(samples, times[i], config.gapSeconds);
        }
        return result;
    }

    private double[] fillCurrent(List<Sample> samples, double[] times) {
        double[] result = fillValue(samples, times);
        if (config.clampNegativeCurrent) {
            for (int i = 0; i < result.length; i++) {
                result[i] = Math.max(0.0, result[i]);
            }
        }
        return result;
    }

    private double[] power(double[] voltage, double[] current) {
        double[] result = new double[voltage.length];
        for (int i = 0; i < voltage.length; i++) {
            if (Double.isFinite(voltage[i]) && Double.isFinite(current[i])) {
                result[i] = voltage[i] * current[i];
            } else {
                result[i] = Double.NaN;
            }
        }
        return result;
    }

    private double integratePower(
            double[] times, boolean[] enabled, List<BoolSample> enabledSamples, double[] power) {
        double energy = 0.0;
        for (int i = 0; i < times.length - 1; i++) {
            double mid = (times[i] + times[i + 1]) / 2.0;
            if (!holdBoolean(enabledSamples, mid)) {
                continue;
            }
            if (!Double.isFinite(power[i]) || !Double.isFinite(power[i + 1])) {
                continue;
            }
            energy += 0.5 * (power[i] + power[i + 1]) * (times[i + 1] - times[i]);
        }
        return energy;
    }

    private double integrateCoverage(
            double[] times, boolean[] enabled, List<BoolSample> enabledSamples, double[] power) {
        double coverage = 0.0;
        for (int i = 0; i < times.length - 1; i++) {
            double mid = (times[i] + times[i + 1]) / 2.0;
            if (!holdBoolean(enabledSamples, mid)) {
                continue;
            }
            if (Double.isFinite(power[i]) && Double.isFinite(power[i + 1])) {
                coverage += times[i + 1] - times[i];
            }
        }
        return coverage;
    }

    private double timeEnabled(List<BoolSample> samples, double start, double end) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < samples.size(); i++) {
            if (!samples.get(i).value) {
                continue;
            }
            double intervalStart = Math.max(samples.get(i).time, start);
            double next = i + 1 < samples.size() ? samples.get(i + 1).time : end;
            double intervalEnd = Math.min(next, end);
            if (intervalEnd > intervalStart) {
                total += intervalEnd - intervalStart;
            }
        }
        return total;
    }

    private int windowSamples(double seconds) {
        return Math.max(1, (int) Math.round(seconds * config.gridHz));
    }

    private double minRollingMean(double[] values, boolean[] enabled, int window) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            if (!enabled[i]) {
                continue;
            }
            double mean = rollingMeanAt(values, enabled, i, window);
            if (Double.isFinite(mean)) {
                min = Math.min(min, mean);
            }
        }
        return Double.isFinite(min) ? min : Double.NaN;
    }

    private double maxRollingMean(double[] values, boolean[] enabled, int window) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            if (!enabled[i]) {
                continue;
            }
            double mean = rollingMeanAt(values, enabled, i, window);
            if (Double.isFinite(mean)) {
                max = Math.max(max, mean);
            }
        }
        return Double.isFinite(max) ? max : Double.NaN;
    }

    private double rollingMeanAt(double[] values, boolean[] enabled, int end, int window) {
        if (end + 1 < window) {
            return Double.NaN;
        }
        int start = Math.max(0, end - window + 1);
        double sum = 0.0;
        int count = 0;
        for (int i = start; i <= end; i++) {
            if (!enabled[i] || !Double.isFinite(values[i])) {
                return Double.NaN;
            }
            sum += values[i];
            count++;
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    private double minValue(double[] values, boolean[] enabled) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            if (enabled[i] && Double.isFinite(values[i])) {
                min = Math.min(min, values[i]);
            }
        }
        return Double.isFinite(min) ? min : Double.NaN;
    }

    private double maxValue(double[] values, boolean[] enabled) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            if (enabled[i] && Double.isFinite(values[i])) {
                max = Math.max(max, values[i]);
            }
        }
        return Double.isFinite(max) ? max : Double.NaN;
    }

    private double mean(double[] values, boolean[] enabled) {
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            if (enabled[i] && Double.isFinite(values[i])) {
                sum += values[i];
                count++;
            }
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    private static double interpolate(List<Sample> samples, double time, double gapSeconds) {
        if (samples.isEmpty()) {
            return Double.NaN;
        }
        int index = lowerBound(samples, time);
        if (index < samples.size() && Math.abs(samples.get(index).time - time) < 1e-9) {
            return samples.get(index).value;
        }
        if (index == 0 || index == samples.size()) {
            return Double.NaN;
        }
        Sample before = samples.get(index - 1);
        Sample after = samples.get(index);
        if (after.time - before.time > gapSeconds) {
            return Double.NaN;
        }
        double fraction = (time - before.time) / (after.time - before.time);
        return before.value + fraction * (after.value - before.value);
    }

    private static boolean holdBoolean(List<BoolSample> samples, double time) {
        if (samples.isEmpty()) {
            return false;
        }
        int index = lowerBoundBool(samples, time);
        if (index < samples.size() && Math.abs(samples.get(index).time - time) < 1e-9) {
            return samples.get(index).value;
        }
        if (index == 0) {
            return samples.get(0).value;
        }
        return samples.get(index - 1).value;
    }

    private static int lowerBound(List<Sample> samples, double time) {
        int low = 0;
        int high = samples.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (samples.get(mid).time < time) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private static int lowerBoundBool(List<BoolSample> samples, double time) {
        int low = 0;
        int high = samples.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (samples.get(mid).time < time) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private static double minTime(
            List<Sample> voltage,
            List<BoolSample> enabled,
            List<Sample> totalCurrent,
            Map<String, List<Sample>> subsystemSeries,
            Map<String, List<Sample>> motorSeries) {
        double min = Double.POSITIVE_INFINITY;
        min = Math.min(min, firstTime(voltage));
        min = Math.min(min, firstTimeBool(enabled));
        min = Math.min(min, firstTime(totalCurrent));
        for (List<Sample> series : subsystemSeries.values()) {
            min = Math.min(min, firstTime(series));
        }
        for (List<Sample> series : motorSeries.values()) {
            min = Math.min(min, firstTime(series));
        }
        return min;
    }

    private static double maxTime(
            List<Sample> voltage,
            List<BoolSample> enabled,
            List<Sample> totalCurrent,
            Map<String, List<Sample>> subsystemSeries,
            Map<String, List<Sample>> motorSeries) {
        double max = Double.NEGATIVE_INFINITY;
        max = Math.max(max, lastTime(voltage));
        max = Math.max(max, lastTimeBool(enabled));
        max = Math.max(max, lastTime(totalCurrent));
        for (List<Sample> series : subsystemSeries.values()) {
            max = Math.max(max, lastTime(series));
        }
        for (List<Sample> series : motorSeries.values()) {
            max = Math.max(max, lastTime(series));
        }
        return max;
    }

    private static double firstTime(List<Sample> samples) {
        return samples.isEmpty() ? Double.POSITIVE_INFINITY : samples.get(0).time;
    }

    private static double lastTime(List<Sample> samples) {
        return samples.isEmpty() ? Double.NEGATIVE_INFINITY : samples.get(samples.size() - 1).time;
    }

    private static double firstTimeBool(List<BoolSample> samples) {
        return samples.isEmpty() ? Double.POSITIVE_INFINITY : samples.get(0).time;
    }

    private static double lastTimeBool(List<BoolSample> samples) {
        return samples.isEmpty() ? Double.NEGATIVE_INFINITY : samples.get(samples.size() - 1).time;
    }

    private static List<Sample> firstSeries(Map<String, List<Sample>> map, String key) {
        return map.getOrDefault(key, List.of());
    }

    private static List<BoolSample> firstBoolSeries(Map<String, List<BoolSample>> map, String key) {
        return map.getOrDefault(key, List.of());
    }

    private static boolean hasPositive(List<Sample> samples) {
        for (Sample sample : samples) {
            if (sample.value > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean lastBoolean(
            Map<String, List<BoolSample>> map, String key, boolean fallback) {
        List<BoolSample> samples = map.get(key);
        return samples == null || samples.isEmpty() ? fallback : samples.get(samples.size() - 1).value;
    }

    private static String lastString(Map<String, List<StringSample>> map, String key) {
        List<StringSample> samples = map.get(key);
        return samples == null || samples.isEmpty() ? null : samples.get(samples.size() - 1).value;
    }

    private static long lastLong(Map<String, List<LongSample>> map, String key, long fallback) {
        List<LongSample> samples = map.get(key);
        return samples == null || samples.isEmpty() ? fallback : samples.get(samples.size() - 1).value;
    }

    private static double medianDelta(List<Sample> samples) {
        if (samples.size() < 2) {
            return Double.NaN;
        }
        List<Double> deltas = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            deltas.add(samples.get(i).time - samples.get(i - 1).time);
        }
        deltas.sort(Double::compare);
        return deltas.get(deltas.size() / 2);
    }

    private static void addMissing(List<String> missing, String key) {
        if (!missing.contains(key)) {
            missing.add(key);
        }
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalize(String name) {
        String normalized = name;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("Robot/")) {
            normalized = normalized.substring("Robot/".length());
        }
        return normalized;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static final class EntryInfo {
        final String name;
        final String type;

        EntryInfo(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private static final class Sample {
        final double time;
        final double value;

        Sample(double time, double value) {
            this.time = time;
            this.value = value;
        }
    }

    private static final class BoolSample {
        final double time;
        final boolean value;

        BoolSample(double time, boolean value) {
            this.time = time;
            this.value = value;
        }
    }

    private static final class LongSample {
        final double time;
        final long value;

        LongSample(double time, long value) {
            this.time = time;
            this.value = value;
        }
    }

    private static final class StringSample {
        final double time;
        final String value;

        StringSample(double time, String value) {
            this.time = time;
            this.value = value;
        }
    }

    private static final class LogData {
        final Map<String, List<Sample>> doubles = new LinkedHashMap<>();
        final Map<String, List<LongSample>> integers = new LinkedHashMap<>();
        final Map<String, List<BoolSample>> booleans = new LinkedHashMap<>();
        final Map<String, List<StringSample>> strings = new LinkedHashMap<>();

        boolean hasKey(String key) {
            return doubles.containsKey(key)
                    || integers.containsKey(key)
                    || booleans.containsKey(key)
                    || strings.containsKey(key);
        }
    }

    public static final class ParseResult {
        public final Map<String, Object> summary;
        public final Map<String, Object> series;
        public final Map<String, Object> indexEntry;
        public final boolean complete;

        ParseResult(
                Map<String, Object> summary,
                Map<String, Object> series,
                Map<String, Object> indexEntry,
                boolean complete) {
            this.summary = summary;
            this.series = series;
            this.indexEntry = indexEntry;
            this.complete = complete;
        }
    }
}
