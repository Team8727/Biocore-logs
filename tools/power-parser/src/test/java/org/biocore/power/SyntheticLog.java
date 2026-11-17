package org.biocore.power;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Writes deterministic WPILog fixtures for parser tests and the demo site. */
public final class SyntheticLog {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int nextEntryId = 1;

    public static void main(String[] args) throws Exception {
        byte[] bytes =
                args.length > 1 && args[1].equals("--two") ? buildTwoSessions() : build();
        if (args.length > 0) {
            java.nio.file.Path output = java.nio.file.Path.of(args[0]);
            java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.write(output, bytes);
            System.out.println("Wrote " + output + " (" + bytes.length + " bytes)");
        } else {
            System.out.println("Synthetic WPILog bytes: " + bytes.length);
        }
    }

    public static byte[] build() throws IOException {
        SyntheticLog log = new SyntheticLog();
        log.writeHeader();
        Ids ids = log.createEntries();
        log.writeMetadata(ids, 0.0, 42, 1);
        log.writeRange(ids, 0.0, 10.0);
        return log.out.toByteArray();
    }

    /** Two enabled windows separated by 60 s and a match-number change. */
    public static byte[] buildTwoSessions() throws IOException {
        SyntheticLog log = new SyntheticLog();
        log.writeHeader();
        Ids ids = log.createEntries();
        log.writeMetadata(ids, 0.0, 1, 0);
        log.writeRange(ids, 0.0, 10.0);
        log.writeMetadata(ids, 70.0, 2, 0);
        log.writeRange(ids, 70.0, 80.0);
        return log.out.toByteArray();
    }

    private Ids createEntries() {
        Ids ids = new Ids();
        ids.voltage = startEntry("Power/Battery/Voltage", "double", "");
        ids.enabled = startEntry("Power/State/Enabled", "boolean", "");
        ids.totalCurrent = startEntry("Power/Battery/TotalCurrent", "double", "");
        ids.motorTotal = startEntry("Power/Motors/TotalCurrent", "double", "");
        ids.pdhAvailable = startEntry("Power/Battery/PDHAvailable", "boolean", "");
        ids.drivetrain = startEntry("Power/Subsystem/Drivetrain/Current", "double", "");
        ids.drive0 = startEntry(
                "Power/Subsystem/Drivetrain/Motor/Drive0/Current", "double", "");
        ids.drive1 = startEntry(
                "Power/Subsystem/Drivetrain/Motor/Drive1/Current", "double", "");
        ids.drive2 = startEntry(
                "Power/Subsystem/Drivetrain/Motor/Drive2/Current", "double", "");
        ids.drive3 = startEntry(
                "Power/Subsystem/Drivetrain/Motor/Drive3/Current", "double", "");
        ids.intake = startEntry("Power/Subsystem/Intake/Current", "double", "");
        ids.intakeRoller = startEntry(
                "Power/Subsystem/Intake/Motor/Roller/Current", "double", "");
        ids.intakeConveyor = startEntry(
                "Power/Subsystem/Intake/Motor/Conveyor/Current", "double", "");
        ids.shooter = startEntry("Power/Subsystem/Shooter/Current", "double", "");
        ids.shooterFlywheel = startEntry(
                "Power/Subsystem/Shooter/Motor/Flywheel/Current", "double", "");
        ids.eventName = startEntry("Power/Meta/EventName", "string", "");
        ids.matchNumber = startEntry("Power/Meta/MatchNumber", "int64", "");
        ids.replayNumber = startEntry("Power/Meta/ReplayNumber", "int64", "");
        ids.matchType = startEntry("Power/Meta/MatchType", "string", "");
        ids.teamNumber = startEntry("Power/Meta/TeamNumber", "int64", "");
        ids.robotSerial = startEntry("Power/Meta/RobotSerial", "string", "");
        ids.schemaVersion = startEntry("Power/Meta/SchemaVersion", "int64", "");
        return ids;
    }

    private void writeMetadata(Ids ids, double time, long matchNumber, long replay) {
        long timestamp = micros(time);
        appendString(ids.eventName, "Test Event", timestamp);
        appendInteger(ids.matchNumber, matchNumber, timestamp);
        appendInteger(ids.replayNumber, replay, timestamp);
        appendString(ids.matchType, "Qualification", timestamp);
        appendInteger(ids.teamNumber, 9999, timestamp);
        appendString(ids.robotSerial, "TEST-ROBOT", timestamp);
        appendInteger(ids.schemaVersion, 1, timestamp);
    }

    private void writeRange(Ids ids, double start, double end) {
        int first = (int) Math.round(start * 50.0);
        int last = (int) Math.round(end * 50.0);
        for (int i = first; i <= last; i++) {
            double time = i / 50.0;
            long timestamp = micros(time);
            double local = time - start;
            boolean isEnabled = local >= 1.0 && local <= 9.0;
            double voltage = (local >= 4.0 && local < 4.5) ? 8.5 : 12.5;
            double current;
            if (local < 1.0 || local > 9.0) {
                current = 0.0;
            } else if (local < 3.0) {
                current = 100.0;
            } else if (local < 5.0) {
                current = 200.0;
            } else {
                current = 50.0;
            }

            appendDouble(ids.voltage, voltage, timestamp);
            appendBoolean(ids.enabled, isEnabled, timestamp);
            appendDouble(ids.totalCurrent, current, timestamp);
            appendDouble(ids.motorTotal, current, timestamp);
            appendBoolean(ids.pdhAvailable, true, timestamp);
            appendDouble(ids.drivetrain, current * 0.65, timestamp);
            appendDouble(ids.drive0, current * 0.1625, timestamp);
            appendDouble(ids.drive1, current * 0.1625, timestamp);
            appendDouble(ids.drive2, current * 0.1625, timestamp);
            appendDouble(ids.drive3, current * 0.1625, timestamp);
            appendDouble(ids.intake, current * 0.10, timestamp);
            appendDouble(ids.intakeRoller, current * 0.05, timestamp);
            appendDouble(ids.intakeConveyor, current * 0.05, timestamp);
            appendDouble(ids.shooter, current * 0.05, timestamp);
            appendDouble(ids.shooterFlywheel, current * 0.05, timestamp);
        }
    }

    private static long micros(double seconds) {
        return Math.round(seconds * 1_000_000.0);
    }

    private void writeHeader() {
        out.writeBytes("WPILOG".getBytes(StandardCharsets.UTF_8));
        out.write(0x00);
        out.write(0x01);
        writeLittleEndian(0, 4);
    }

    private int startEntry(String name, String type, String metadata) {
        int id = nextEntryId++;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0);
        writeLittleEndian(payload, id, 4);
        writeString(payload, name);
        writeString(payload, type);
        writeString(payload, metadata);
        writeRecord(0, 0, payload.toByteArray());
        return id;
    }

    private void appendDouble(int id, double value, long timestamp) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putDouble(value);
        writeRecord(id, timestamp, buffer.array());
    }

    private void appendInteger(int id, long value, long timestamp) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(value);
        writeRecord(id, timestamp, buffer.array());
    }

    private void appendBoolean(int id, boolean value, long timestamp) {
        writeRecord(id, timestamp, new byte[] {(byte) (value ? 1 : 0)});
    }

    private void appendString(int id, String value, long timestamp) {
        writeRecord(id, timestamp, value.getBytes(StandardCharsets.UTF_8));
    }

    private void writeRecord(int entryId, long timestamp, byte[] payload) {
        int idLen = bytesNeeded(entryId);
        int sizeLen = bytesNeeded(payload.length);
        int tsLen = bytesNeeded(timestamp);
        int bitfield = (idLen - 1) | ((sizeLen - 1) << 2) | ((tsLen - 1) << 4);

        out.write(bitfield);
        writeLittleEndian(entryId, idLen);
        writeLittleEndian(payload.length, sizeLen);
        writeLittleEndian(timestamp, tsLen);
        out.writeBytes(payload);
    }

    private void writeLittleEndian(long value, int length) {
        for (int i = 0; i < length; i++) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }

    private static void writeLittleEndian(ByteArrayOutputStream out, long value, int length) {
        for (int i = 0; i < length; i++) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeLittleEndian(out, bytes.length, 4);
        out.writeBytes(bytes);
    }

    private static int bytesNeeded(long value) {
        if (value == 0) {
            return 1;
        }
        int bytes = 0;
        long remaining = value;
        while (remaining != 0) {
            bytes++;
            remaining >>>= 8;
        }
        return Math.min(8, bytes);
    }

    private static final class Ids {
        int voltage;
        int enabled;
        int totalCurrent;
        int motorTotal;
        int pdhAvailable;
        int drivetrain;
        int drive0;
        int drive1;
        int drive2;
        int drive3;
        int intake;
        int intakeRoller;
        int intakeConveyor;
        int shooter;
        int shooterFlywheel;
        int eventName;
        int matchNumber;
        int replayNumber;
        int matchType;
        int teamNumber;
        int robotSerial;
        int schemaVersion;
    }
}
