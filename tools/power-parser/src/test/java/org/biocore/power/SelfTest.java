package org.biocore.power;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Local integration check; run with the compiled main and test classes. */
public final class SelfTest {
    private SelfTest() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("power-parser-selftest");
        Path log = dir.resolve("synthetic.wpilog");
        Files.write(log, SyntheticLog.build());

        PowerLogParser.ParseResult result =
                new PowerLogParser(new MetricsConfig()).parse(log, null).get(0);
        Map<String, Object> metrics = asMap(result.summary.get("metrics"));

        check("enabled_seconds", 8.0, number(metrics, "enabled_seconds"), 0.05);
        check("total_energy_wh", 2.6667, number(metrics, "total_energy_wh"), 0.05);
        check("lowest_voltage_v", 8.5, number(metrics, "lowest_voltage_v"), 0.05);
        check("sag_count", 1.0, number(metrics, "sag_count"), 0.01);
        check("sag_total_seconds", 0.5, number(metrics, "sag_total_seconds"), 0.05);
        check("peak_current_a", 200.0, number(metrics, "peak_current_a"), 2.0);
        check("peak_power_w", 2500.0, number(metrics, "peak_power_w"), 60.0);

        System.out.println("Self-test passed.");
        System.out.println(SimpleJson.write(result.summary));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static double number(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).doubleValue();
    }

    private static void check(String name, double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(
                    name + " expected " + expected + " +/- " + tolerance + " but was " + actual);
        }
        System.out.printf("  %-22s %.4f%n", name, actual);
    }
}
