package io.github.magnusp.turso.conformance;

import io.github.magnusp.turso.JDBC;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Enumeration;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Test-only shim that maps the {@code jdbc:sqlite:} URLs used by the exported reference tests onto
 * the embedded Turso driver.
 *
 * <p>An empty path, {@code :memory:}, {@code file::memory:?cache=shared}, and any URL whose query
 * selects {@code mode=memory} map to {@code jdbc:turso::memory:}. A non-memory path is resolved
 * against the process working directory, normalized, and passed to Turso as a local file. The
 * query string is dropped because the embedded driver has no URL pragma or shared-cache surface;
 * connection {@link Properties} are passed through unchanged.
 *
 * <p>HTTP and HTTPS URLs are rejected. This driver is test-only and is never part of the shipped
 * jar.
 */
public final class TursoConformanceDriver implements Driver {

    private static final String PREFIX = "jdbc:sqlite:";
    private static final String TURSO_PREFIX = "jdbc:turso:";
    private static final String FILE_SCHEME = "file:";
    private static final String MEMORY_PATH = ":memory:";
    private static final String MEMORY_URL = TURSO_PREFIX + MEMORY_PATH;

    private final JDBC delegate = new JDBC();

    /** Registers this shim once, even when the service file and the launcher both call it. */
    public static synchronized void register() {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            if (drivers.nextElement().getClass() == TursoConformanceDriver.class) {
                return;
            }
        }
        try {
            DriverManager.registerDriver(new TursoConformanceDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Maps a {@code jdbc:sqlite:} URL to the equivalent {@code jdbc:turso:} URL.
     *
     * @param url a URL accepted by this driver
     * @return the embedded Turso URL
     * @throws IllegalArgumentException when the URL is not a supported {@code jdbc:sqlite:} URL
     */
    public static String map(String url) {
        if (!accepts(url)) {
            throw new IllegalArgumentException("not a supported jdbc:sqlite: URL: " + url);
        }

        String target = url.substring(PREFIX.length());
        boolean fileUri = target.startsWith(FILE_SCHEME);
        String withoutScheme = fileUri ? target.substring(FILE_SCHEME.length()) : target;
        String path = stripQuery(withoutScheme);
        String query = queryOf(withoutScheme);

        boolean memory = path.isEmpty() || MEMORY_PATH.equals(path);
        if (!memory && fileUri) {
            memory = hasMemoryMode(query);
        }
        if (memory) {
            return MEMORY_URL;
        }

        String normalized = Path.of(path).toAbsolutePath().normalize().toString();
        return TURSO_PREFIX + normalized;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!accepts(url)) {
            return null;
        }
        return JDBC.createConnection(map(url), info);
    }

    @Override
    public boolean acceptsURL(String url) {
        return accepts(url);
    }

    private static boolean accepts(String url) {
        if (url == null || !url.startsWith(PREFIX)) {
            return false;
        }
        String target = url.substring(PREFIX.length());
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return false;
        }
        return !target.startsWith(FILE_SCHEME + "http://")
                && !target.startsWith(FILE_SCHEME + "https://");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    private static String stripQuery(String target) {
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    private static String queryOf(String target) {
        int query = target.indexOf('?');
        return query < 0 ? "" : target.substring(query + 1);
    }

    private static boolean hasMemoryMode(String query) {
        for (String parameter : query.split("&")) {
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            String value = equals < 0 ? "" : parameter.substring(equals + 1);
            if ("mode".equals(name) && "memory".equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
