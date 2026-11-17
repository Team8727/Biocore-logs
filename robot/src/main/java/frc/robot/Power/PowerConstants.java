package frc.robot.Power;

/** Configuration for the robot-side power telemetry layer. */
public final class PowerConstants {
    private PowerConstants() {}

    /** Bump when the logged key schema changes. */
    public static final int SCHEMA_VERSION = 1;

    /** PDH/PDP CAN ID. Verify against the electrical lead and docs/wiring.md. */
    public static final int PDH_CAN_ID = 1;

    /** Set false on a robot without a PDH/PDP. The logger falls back to motor current. */
    public static final boolean USE_PDH = true;
}
