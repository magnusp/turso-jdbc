package io.github.magnusp.turso.conformance;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;

/**
 * Selects the upstream xerial driver for the parity control run.
 *
 * <p>The control run compiles the same pinned xerial test tree as the Turso run but executes it
 * against sqlite-jdbc 3.53.4.0. Both drivers are on the test class path, so the control run first
 * removes every driver visible to this class loader and then registers the pinned xerial driver
 * explicitly. That keeps {@code jdbc:sqlite:} URLs from reaching the Turso shim.
 */
final class TursoControl {

    static final String ENABLED_PROPERTY = "conformance.control.driver";
    static final String XERIAL_DRIVER = "org.sqlite.JDBC";

    private static final String PROBE_URL = "jdbc:sqlite::memory:";

    private TursoControl() {}

    static boolean isEnabled() {
        return "xerial".equals(System.getProperty(ENABLED_PROPERTY));
    }

    static void register() {
        deregisterVisibleDrivers();
        try {
            Class<?> driverClass = Class.forName(XERIAL_DRIVER);
            if (!alreadyRegistered()) {
                Driver driver = (Driver) driverClass.getConstructor().newInstance();
                DriverManager.registerDriver(driver);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Xerial control run could not load " + XERIAL_DRIVER, e);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Xerial control run could not register " + XERIAL_DRIVER, e);
        }
        verify();
    }

    private static boolean alreadyRegistered() {
        try {
            return XERIAL_DRIVER.equals(
                    DriverManager.getDriver(PROBE_URL).getClass().getName());
        } catch (SQLException noDriver) {
            return false;
        }
    }

    private static void deregisterVisibleDrivers() {
        ClassLoader classLoader = TursoControl.class.getClassLoader();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == classLoader) {
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (SQLException e) {
                    throw new IllegalStateException(
                            "Xerial control run could not deregister " + driver, e);
                }
            }
        }
    }

    private static void verify() {
        try {
            Driver driver = DriverManager.getDriver(PROBE_URL);
            if (!XERIAL_DRIVER.equals(driver.getClass().getName())) {
                throw new IllegalStateException(
                        "Xerial control run resolved "
                                + driver.getClass().getName()
                                + " for "
                                + PROBE_URL
                                + " instead of "
                                + XERIAL_DRIVER);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Xerial control run found no driver for " + PROBE_URL, e);
        }
    }
}
