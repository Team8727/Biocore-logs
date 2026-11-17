package org.biocore.power;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Rebuilds the public session index from per-session index entries. */
public final class IndexBuilder {
    private IndexBuilder() {}

    public static void rebuild(Path outputDir) throws IOException {
        Path sessionsDir = outputDir.resolve("sessions");
        List<Map<String, Object>> entries = new ArrayList<>();

        if (Files.isDirectory(sessionsDir)) {
            try (Stream<Path> stream = Files.list(sessionsDir)) {
                List<Path> files = stream
                        .filter(path -> path.getFileName().toString().endsWith(".index.json"))
                        .sorted()
                        .toList();
                for (Path file : files) {
                    entries.add(SimpleJson.parseObject(Files.readString(file, StandardCharsets.UTF_8)));
                }
            }
        }

        entries.sort(
                Comparator.comparing(
                                (Map<String, Object> entry) ->
                                        SimpleJson.getString(entry, "processed_at", ""))
                        .reversed()
                        .thenComparingInt(
                                entry -> SimpleJson.getInt(entry, "session_index", 0)));

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("schema_version", PowerLogParser.SCHEMA_VERSION);
        index.put("generated_at", Instant.now().toString());
        index.put("sessions", entries);

        Files.createDirectories(outputDir);
        Files.writeString(
                outputDir.resolve("index.json"),
                SimpleJson.write(index) + "\n",
                StandardCharsets.UTF_8);
    }
}
