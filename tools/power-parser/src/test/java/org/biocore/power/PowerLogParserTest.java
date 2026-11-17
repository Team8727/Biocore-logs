package org.biocore.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PowerLogParserTest {
    @TempDir Path tempDir;

    @Test
    void parsesSyntheticLog() throws Exception {
        Path log = tempDir.resolve("synthetic.wpilog");
        Files.write(log, SyntheticLog.build());

        PowerLogParser parser = new PowerLogParser(new MetricsConfig());
        java.util.List<PowerLogParser.ParseResult> results = parser.parse(log, null);
        assertEquals(1, results.size());
        PowerLogParser.ParseResult result = results.get(0);

        assertTrue(result.complete);
        Map<String, Object> metrics = asMap(result.summary.get("metrics"));
        Map<String, Object> quality = asMap(result.summary.get("data_quality"));
        Map<String, Object> metadata = asMap(result.summary.get("metadata"));

        assertEquals(8.0, number(metrics, "enabled_seconds"), 0.05);
        assertEquals(2.6667, number(metrics, "total_energy_wh"), 0.05);
        assertEquals(8.5, number(metrics, "lowest_voltage_v"), 0.05);
        assertEquals(1.0, number(metrics, "sag_count"), 0.01);
        assertEquals(0.5, number(metrics, "sag_total_seconds"), 0.05);
        assertEquals(200.0, number(metrics, "peak_current_a"), 2.0);
        assertEquals(2500.0, number(metrics, "peak_power_w"), 60.0);
        assertEquals("pdh", quality.get("total_current_source"));
        assertEquals("Test Event", metadata.get("event_name"));
        assertEquals(42.0, number(metadata, "match_number"), 0.01);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subsystems = (List<Map<String, Object>>) result.summary.get("subsystems");
        assertNotNull(subsystems);
        assertEquals(3, subsystems.size());
        assertEquals("Drivetrain", subsystems.get(0).get("name"));
        assertEquals(1.7333, number(subsystems.get(0), "energy_wh"), 0.05);
        assertEquals(
                2.1333,
                subsystems.stream().mapToDouble(item -> number(item, "energy_wh")).sum(),
                0.05);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> motors = (List<Map<String, Object>>) result.summary.get("motors");
        assertNotNull(motors);
        assertEquals(7, motors.size());
        assertEquals(0.4333, number(motors.get(0), "energy_wh"), 0.03);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sags = (List<Map<String, Object>>) result.summary.get("sags");
        assertEquals(1, sags.size());
        assertEquals(0.5, number(sags.get(0), "duration_s"), 0.05);
        assertEquals(8.5, number(sags.get(0), "min_v"), 0.01);
    }

    @Test
    void splitsTwoSessionsFromOneLog() throws Exception {
        Path log = tempDir.resolve("two.wpilog");
        Files.write(log, SyntheticLog.buildTwoSessions());

        List<PowerLogParser.ParseResult> results =
                new PowerLogParser(new MetricsConfig()).parse(log, null);

        assertEquals(2, results.size());
        assertEquals(2.0, number(asMap(results.get(0).summary.get("metadata")), "session_count"), 0.01);
        assertEquals(0.0, number(asMap(results.get(0).summary.get("metadata")), "session_index"), 0.01);
        assertEquals(1.0, number(asMap(results.get(0).summary.get("metadata")), "match_number"), 0.01);
        assertEquals(2.0, number(asMap(results.get(1).summary.get("metadata")), "match_number"), 0.01);
        assertEquals(8.0, number(asMap(results.get(0).summary.get("metrics")), "enabled_seconds"), 0.1);
        assertEquals(8.0, number(asMap(results.get(1).summary.get("metrics")), "enabled_seconds"), 0.1);
        assertEquals(2.6667, number(asMap(results.get(0).summary.get("metrics")), "total_energy_wh"), 0.05);
        assertEquals(2.6667, number(asMap(results.get(1).summary.get("metrics")), "total_energy_wh"), 0.05);
    }

    @Test
    void writesValidJson() throws Exception {        Path log = tempDir.resolve("synthetic.wpilog");
        Files.write(log, SyntheticLog.build());
        PowerLogParser.ParseResult result =
                new PowerLogParser(new MetricsConfig()).parse(log, null).get(0);

        String summaryJson = SimpleJson.write(result.summary);
        Map<String, Object> parsed = SimpleJson.parseObject(summaryJson);
        assertEquals(result.summary.get("session_id"), parsed.get("session_id"));
        assertNotNull(parsed.get("metrics"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static double number(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).doubleValue();
    }
}
