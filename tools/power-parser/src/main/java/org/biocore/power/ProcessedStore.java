package org.biocore.power;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tracks which log hashes have been processed by which parser version. */
public final class ProcessedStore {
    private final Map<String, Object> root;
    private final Map<String, Object> logs;

    private ProcessedStore(Map<String, Object> root) {
        this.root = root;
        Object existing = root.get("logs");
        this.logs = existing instanceof Map ? castMap(existing) : new LinkedHashMap<>();
        root.put("logs", logs);
    }

    public static ProcessedStore load(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", 1);
            root.put("parser_version", PowerLogParser.PARSER_VERSION);
            return new ProcessedStore(root);
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return new ProcessedStore(SimpleJson.parseObject(text));
    }

    public boolean isProcessed(String sha256, int parserVersion) {
        Object entry = logs.get(sha256);
        if (!(entry instanceof Map)) {
            return false;
        }
        Map<String, Object> map = castMap(entry);
        return SimpleJson.getInt(map, "parser_version", -1) == parserVersion;
    }

    public void mark(String sha256, String sourceFile, String sessionId, int parserVersion) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("source_file", sourceFile);
        entry.put("session_id", sessionId);
        entry.put("processed_at", Instant.now().toString());
        entry.put("parser_version", parserVersion);
        logs.put(sha256, entry);
        root.put("parser_version", parserVersion);
    }

    public void save(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, SimpleJson.write(root) + "\n", StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
