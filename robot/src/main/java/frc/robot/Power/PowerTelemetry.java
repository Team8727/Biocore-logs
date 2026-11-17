package frc.robot.Power;

import dev.doglog.DogLog;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.PowerDistribution;
import edu.wpi.first.wpilibj.RobotController;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * Central power telemetry. Call {@link #periodic()} once per robot loop.
 *
 * <p>DogLog writes into the same WPILib {@code DataLogManager} WPILog under its
 * {@code /Robot} table, so these keys appear as {@code Robot/Power/...} in the
 * log. The parser normalizes that prefix.
 */
public final class PowerTelemetry {
    private static final PowerTelemetry INSTANCE = new PowerTelemetry();

    private final Map<String, Map<String, DoubleSupplier>> motors = new LinkedHashMap<>();
    private final Map<String, Double> subsystemCurrents = new LinkedHashMap<>();

    private PowerDistribution pdh;
    private boolean pdhInitialized;

    private PowerTelemetry() {}

    public static PowerTelemetry getInstance() {
        return INSTANCE;
    }

    /** Initializes the optional PDH/PDP. Safe to call more than once. */
    public void init() {
        if (pdhInitialized) {
            return;
        }
        pdhInitialized = true;

        if (!PowerConstants.USE_PDH) {
            return;
        }

        try {
            pdh = new PowerDistribution(
                    PowerConstants.PDH_CAN_ID, PowerDistribution.ModuleType.kRev);
            DogLog.setPdh(pdh);
        } catch (RuntimeException ex) {
            DriverStation.reportWarning(
                    "PowerTelemetry: PDH init failed, using motor-only current: " + ex.getMessage(),
                    false);
            pdh = null;
        }
    }

    /**
     * Registers a motor supply-current source.
     *
     * @param subsystem stable subsystem name, e.g. {@code Drivetrain}
     * @param motor stable motor name, e.g. {@code Drive0}
     * @param supplyCurrentAmps supplier returning battery-side supply current in amps
     */
    public void registerMotor(
            String subsystem, String motor, DoubleSupplier supplyCurrentAmps) {
        motors.computeIfAbsent(subsystem, key -> new LinkedHashMap<>())
                .put(motor, supplyCurrentAmps);
    }

    /** Logs all power samples. Call from {@code robotPeriodic()}. */
    public void periodic() {
        init();

        double batteryVoltage = RobotController.getBatteryVoltage();
        double pdhTotalCurrent = pdh != null ? pdh.getTotalCurrent() : 0.0;
        boolean pdhAvailable = pdh != null && pdhTotalCurrent > 0.0;
        double motorTotalCurrent = 0.0;

        subsystemCurrents.clear();

        for (Map.Entry<String, Map<String, DoubleSupplier>> subsystemEntry :
                motors.entrySet()) {
            String subsystem = subsystemEntry.getKey();
            double subsystemCurrent = 0.0;

            for (Map.Entry<String, DoubleSupplier> motorEntry :
                    subsystemEntry.getValue().entrySet()) {
                double current = safeCurrent(motorEntry.getValue().getAsDouble());
                motorTotalCurrent += current;
                subsystemCurrent += current;

                DogLog.log(
                        "Power/Subsystem/" + subsystem + "/Motor/" + motorEntry.getKey()
                                + "/Current",
                        current,
                        "A");
            }

            subsystemCurrents.put(subsystem, subsystemCurrent);
            DogLog.log("Power/Subsystem/" + subsystem + "/Current", subsystemCurrent, "A");
        }

        double totalCurrent = pdhAvailable ? pdhTotalCurrent : motorTotalCurrent;

        DogLog.log("Power/Battery/Voltage", batteryVoltage, "V");
        DogLog.log("Power/Battery/TotalCurrent", totalCurrent, "A");
        DogLog.log("Power/Battery/TotalPower", totalCurrent * batteryVoltage, "W");
        DogLog.log("Power/Battery/PDHAvailable", pdhAvailable);
        DogLog.log("Power/Motors/TotalCurrent", motorTotalCurrent, "A");

        logState();
        logMetadata();
    }

    private void logState() {
        DogLog.log("Power/State/Enabled", DriverStation.isEnabled());
        DogLog.log("Power/State/Autonomous", DriverStation.isAutonomous());
        DogLog.log("Power/State/Teleop", DriverStation.isTeleop());
        DogLog.log("Power/State/Test", DriverStation.isTest());
        DogLog.log("Power/State/MatchTime", DriverStation.getMatchTime());
        DogLog.log(
                "Power/State/Alliance",
                DriverStation.getAlliance().map(alliance -> alliance.name()).orElse("Unknown"));
    }

    private void logMetadata() {
        DogLog.log("Power/Meta/SchemaVersion", (long) PowerConstants.SCHEMA_VERSION);
        DogLog.log("Power/Meta/TeamNumber", (long) RobotController.getTeamNumber());
        DogLog.log("Power/Meta/RobotSerial", RobotController.getSerialNumber());
        DogLog.log("Power/Meta/EventName", DriverStation.getEventName());
        DogLog.log("Power/Meta/MatchNumber", (long) DriverStation.getMatchNumber());
        DogLog.log("Power/Meta/ReplayNumber", (long) DriverStation.getReplayNumber());
        DogLog.log("Power/Meta/MatchType", DriverStation.getMatchType().name());
    }

    private static double safeCurrent(double current) {
        if (!Double.isFinite(current)) {
            return 0.0;
        }
        return Math.max(0.0, current);
    }
}
