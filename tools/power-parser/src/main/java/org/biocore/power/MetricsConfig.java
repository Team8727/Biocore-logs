package org.biocore.power;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Thresholds and sampling settings for the power parser. */
public final class MetricsConfig {
    public double sagThresholdV = 9.0;
    public double sagRecoverV = 9.2;
    public double sagMinSeconds = 0.100;
    public double peakWindowSeconds = 0.100;
    public double minVoltageWindowSeconds = 0.100;
    public double gapSeconds = 0.200;
    public double gridHz = 50.0;
    public double seriesHz = 10.0;
    public double sessionMergeGapSeconds = 30.0;
    public boolean clampNegativeCurrent = true;
    public int parserVersion = 1;
    public List<String> requiredKeys =
            List.of("Power/Battery/Voltage", "Power/State/Enabled");

    public static MetricsConfig load(Path path) throws IOException {
        MetricsConfig config = new MetricsConfig();
        if (path == null || !Files.exists(path)) {
            return config;
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        Map<String, Object> map = SimpleJson.parseObject(text);

        config.sagThresholdV = SimpleJson.getDouble(map, "sag_threshold_v", config.sagThresholdV);
        config.sagRecoverV = SimpleJson.getDouble(map, "sag_recover_v", config.sagRecoverV);
        config.sagMinSeconds = SimpleJson.getDouble(map, "sag_min_ms", 100.0) / 1000.0;
        config.peakWindowSeconds = SimpleJson.getDouble(map, "peak_window_ms", 100.0) / 1000.0;
        config.minVoltageWindowSeconds =
                SimpleJson.getDouble(map, "min_voltage_window_ms", 100.0) / 1000.0;
        config.gapSeconds = SimpleJson.getDouble(map, "gap_ms", 200.0) / 1000.0;
        config.gridHz = SimpleJson.getDouble(map, "grid_hz", config.gridHz);
        config.seriesHz = SimpleJson.getDouble(map, "series_hz", config.seriesHz);
        config.sessionMergeGapSeconds =
                SimpleJson.getDouble(map, "session_merge_gap_s", config.sessionMergeGapSeconds);
        config.clampNegativeCurrent =
                SimpleJson.getBoolean(map, "clamp_negative_current", config.clampNegativeCurrent);
        config.parserVersion = SimpleJson.getInt(map, "parser_version", config.parserVersion);

        List<Object> required = SimpleJson.getList(map, "required_keys");
        if (!required.isEmpty()) {
            config.requiredKeys =
                    required.stream().map(String::valueOf).collect(java.util.stream.Collectors.toList());
        }
        return config;
    }
}
