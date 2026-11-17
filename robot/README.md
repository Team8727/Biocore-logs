# Robot-side power telemetry

Drop-in WPILib Java sources for the 2027 robot. They are staged here because this workspace is the local implementation; copy them into `Offseason/offseason-2027/src/main/java/frc/robot/Power/` when approved.

## What it logs

DogLog writes into the same WPILib `DataLogManager` WPILog under its `/Robot` table, so these keys appear as `Robot/Power/...` in the log. The parser normalizes the prefix.

- `Power/Battery/Voltage`, `TotalCurrent`, `TotalPower`, `PDHAvailable`
- `Power/Motors/TotalCurrent`
- `Power/Subsystem/<name>/Current`
- `Power/Subsystem/<name>/Motor/<motor>/Current`
- `Power/State/Enabled`, `Autonomous`, `Teleop`, `Test`, `MatchTime`, `Alliance`
- `Power/Meta/SchemaVersion`, `TeamNumber`, `RobotSerial`, `EventName`, `MatchNumber`, `ReplayNumber`, `MatchType`

## Integration into `Robot.java`

```java
import frc.robot.Power.PowerTelemetry;

@Override
public void robotPeriodic() {
    // ... existing loop timing ...
    container.updateTelemetry(loopTimeMs);
    CommandScheduler.getInstance().run();
    PowerTelemetry.getInstance().periodic();

    // ... existing DogLog calls ...
}
```

## Integration into `RobotContainer.java`

Call this once after the drivetrain is constructed. It registers the four drive and four steer motors.

```java
import frc.robot.Power.PowerTelemetry;

public RobotContainer() {
    drivetrain.setVision(vision);
    telemetry = new DrivetrainTelemetry(drivetrain);
    configurePowerTelemetry();

    configureBindings();
    // ...
}

private void configurePowerTelemetry() {
    PowerTelemetry power = PowerTelemetry.getInstance();
    power.init();

    for (int i = 0; i < 4; i++) {
        final int index = i;
        var module = drivetrain.getModule(index);
        power.registerMotor(
                "Drivetrain",
                "Drive" + index,
                () -> module.getDriveMotor().getSupplyCurrent().getValueAsDouble());
        power.registerMotor(
                "Drivetrain",
                "Steer" + index,
                () -> module.getSteerMotor().getSupplyCurrent().getValueAsDouble());
    }
}
```

Future mechanisms register the same way:

```java
PowerTelemetry.getInstance()
        .registerMotor("Intake", "Roller", () -> intakeMotor.getSupplyCurrent().getValueAsDouble());
```

## PDH/PDP

`PowerConstants.PDH_CAN_ID` defaults to `1`. Verify the CAN ID and module type with the electrical lead against `docs/wiring.md`. If the robot has no PDH/PDP, set `USE_PDH = false`; total draw becomes the sum of registered motor supply currents and the site labels it **motor-only**.

## Why supply current

Supply current is the battery-side draw used for power budgeting. Stator/torque current is motor-side and must not be used for energy totals.

## Verify

1. Deploy and run one match.
2. Download the `.wpilog` with the uploader or AdvantageScope.
3. Confirm `Robot/Power/Battery/Voltage`, `Robot/Power/State/Enabled`, and the eight motor keys are present at about 50 Hz.
4. Run the parser on that log and compare energy and peak draw to AdvantageScope.
