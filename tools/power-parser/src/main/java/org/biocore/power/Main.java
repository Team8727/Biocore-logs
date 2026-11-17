package org.biocore.power;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** CLI entry point for the power parser. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        List<Path> inputs = new ArrayList<>();
        Path outputDir = Path.of("data");
        Path configPath = Path.of("config/power-metrics.json");
        Path processedPath = null;
        boolean skipProcessed = false;
        boolean indexOnly = false;
        boolean strict = false;
        String rawUrl = null;
        int parserVersion = PowerLogParser.PARSER_VERSION;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--input":
                    inputs.add(Path.of(requireValue(args, ++i, arg)));
                    break;
                case "--output-dir":
                    outputDir = Path.of(requireValue(args, ++i, arg));
                    break;
                case "--config":
                    configPath = Path.of(requireValue(args, ++i, arg));
                    break;
                case "--processed":
                    processedPath = Path.of(requireValue(args, ++i, arg));
                    break;
                case "--raw-url":
                    rawUrl = requireValue(args, ++i, arg);
                    break;
                case "--parser-version":
                    parserVersion = Integer.parseInt(requireValue(args, ++i, arg));
                    break;
                case "--skip-processed":
                    skipProcessed = true;
                    break;
                case "--index-only":
                    indexOnly = true;
                    break;
                case "--strict":
                    strict = true;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return;
                default:
                    System.err.println("Unknown argument: " + arg);
                    printUsage();
                    System.exit(2);
            }
        }

        if (processedPath == null) {
            processedPath = outputDir.resolve("processed.json");
        }

        try {
            if (indexOnly) {
                IndexBuilder.rebuild(outputDir);
                System.out.println("Rebuilt " + outputDir.resolve("index.json"));
                return;
            }

            if (inputs.isEmpty()) {
                printUsage();
                System.exit(2);
            }

            MetricsConfig config = MetricsConfig.load(configPath);
            config.parserVersion = parserVersion;
            PowerLogParser parser = new PowerLogParser(config);
            ProcessedStore store = ProcessedStore.load(processedPath);
            Path sessionsDir = outputDir.resolve("sessions");
            Files.createDirectories(sessionsDir);

            int failures = 0;
            int parsed = 0;
            int skipped = 0;

            for (Path input : expandInputs(inputs)) {
                try {
                    String sha = PowerLogParser.fileSha256(input);
                    if (skipProcessed && store.isProcessed(sha, parserVersion)) {
                        System.out.println("Skipping already processed: " + input);
                        skipped++;
                        continue;
                    }

                    List<PowerLogParser.ParseResult> results = parser.parse(input, rawUrl);
                    if (results.isEmpty()) {
                        throw new IOException("parser returned no sessions");
                    }

                    List<String> sessionIds = new ArrayList<>();
                    boolean anyIncomplete = false;

                    for (PowerLogParser.ParseResult result : results) {
                        String sessionId = String.valueOf(result.summary.get("session_id"));
                        sessionIds.add(sessionId);

                        writeJson(sessionsDir.resolve(sessionId + ".summary.json"), result.summary);
                        writeJson(sessionsDir.resolve(sessionId + ".series.json"), result.series);
                        writeJson(sessionsDir.resolve(sessionId + ".index.json"), result.indexEntry);
                        parsed++;

                        Map<String, Object> metrics = asMap(result.summary.get("metrics"));
                        System.out.printf(
                                "Parsed %s -> %s (complete=%s, energy=%.3f Wh, enabled=%.1f s)%n",
                                input.getFileName(),
                                sessionId,
                                result.complete,
                                SimpleJson.getDouble(metrics, "total_energy_wh", 0.0),
                                SimpleJson.getDouble(metrics, "enabled_seconds", 0.0));

                        if (!result.complete) {
                            anyIncomplete = true;
                            System.err.println(
                                    "Warning: incomplete power data in " + input.getFileName());
                        }
                    }

                    store.mark(
                            sha,
                            input.getFileName().toString(),
                            String.join(",", sessionIds),
                            parserVersion);
                    if (anyIncomplete && strict) {
                        failures++;
                    }
                } catch (Exception ex) {
                    failures++;
                    System.err.println("Failed to parse " + input + ": " + ex.getMessage());
                    ex.printStackTrace(System.err);
                }
            }

            store.save(processedPath);
            IndexBuilder.rebuild(outputDir);
            System.out.printf(
                    "Done: parsed=%d skipped=%d failed=%d index=%s%n",
                    parsed, skipped, failures, outputDir.resolve("index.json"));

            if (failures > 0) {
                System.exit(1);
            }
        } catch (Exception ex) {
            System.err.println("Fatal: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static List<Path> expandInputs(List<Path> inputs) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (Stream<Path> stream = Files.list(input)) {
                    stream.filter(path -> path.getFileName().toString().endsWith(".wpilog"))
                            .sorted()
                            .forEach(files::add);
                }
            } else {
                files.add(input);
            }
        }
        if (files.isEmpty()) {
            throw new IOException("No .wpilog inputs found");
        }
        return files;
    }

    private static void writeJson(Path path, Map<String, Object> value) throws IOException {
        Files.writeString(path, SimpleJson.write(value) + "\n", StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            System.err.println("Missing value for " + flag);
            System.exit(2);
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println(
                "Usage: power-parser --input <file-or-dir> [--input ...] [options]\n"
                        + "  --output-dir <dir>       default: data\n"
                        + "  --config <file>          default: config/power-metrics.json\n"
                        + "  --processed <file>       default: <output-dir>/processed.json\n"
                        + "  --raw-url <url>          optional raw log download URL\n"
                        + "  --parser-version <n>     default: "
                        + PowerLogParser.PARSER_VERSION
                        + "\n"
                        + "  --skip-processed         skip hashes already processed by this parser\n"
                        + "  --index-only             rebuild index.json only\n"
                        + "  --strict                 exit nonzero if a log is incomplete\n");
    }
}
