package io.github.magnusp.turso.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The checked identity of the native library bundled in this jar.
 *
 * <p>The properties resource at {@link #RESOURCE_PATH} is the single source of truth for the native
 * pin. {@link TursoNative} loads it before the library lookup and verifies the version, size, and
 * SHA-256 against it.
 *
 * @param platform the native platform directory, currently {@code linux-x86_64}
 * @param tursoCommit the Turso source commit the library was built from
 * @param tursoSdkKitVersion the expected {@code turso_version()} value
 * @param soFile the bundled library file name
 * @param soSize the expected library size in bytes
 * @param soSha256 the expected lower-case SHA-256 of the library
 * @param soGlibcMax the highest glibc symbol version the library requires
 * @param buildImage the container image or distribution the library was built on
 * @param buildGlibc the glibc version of the build environment
 * @param rustVersion the Rust compiler version used for the build
 * @param tursoHeaderSha256 the SHA-256 of {@code sdk-kit/turso.h} at {@code tursoCommit}
 */
record NativeManifest(
    String platform,
    String tursoCommit,
    String tursoSdkKitVersion,
    String soFile,
    long soSize,
    String soSha256,
    String soGlibcMax,
    String buildImage,
    String buildGlibc,
    String rustVersion,
    String tursoHeaderSha256) {

  static final String RESOURCE_PATH =
      "/io/github/magnusp/turso/native/linux-x86_64/native.properties";

  private static final List<String> REQUIRED_KEYS =
      List.of(
          "PLATFORM",
          "TURSO_COMMIT",
          "TURSO_SDK_KIT_VERSION",
          "SO_FILE",
          "SO_SIZE",
          "SO_SHA256",
          "SO_GLIBC_MAX",
          "BUILD_IMAGE",
          "BUILD_GLIBC",
          "RUST_VERSION",
          "TURSO_HEADER_SHA256");

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern GLIBC = Pattern.compile("[0-9]+\\.[0-9]+(\\.[0-9]+)?");

  static NativeManifest load() {
    Properties properties = new Properties();
    try (InputStream input = TursoNative.class.getResourceAsStream(RESOURCE_PATH)) {
      if (input == null) {
        throw new InternalError(
            "Missing native manifest "
                + RESOURCE_PATH
                + " on the classpath. The jar is incomplete; rebuild it with"
                + " scripts/build-native.sh.");
      }
      try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
        properties.load(reader);
      }
    } catch (IOException e) {
      throw new InternalError("Unable to read native manifest " + RESOURCE_PATH, e);
    }

    for (String key : REQUIRED_KEYS) {
      if (value(properties, key).isEmpty()) {
        throw malformed("missing or empty " + key);
      }
    }

    String platform = value(properties, "PLATFORM");
    String tursoCommit = value(properties, "TURSO_COMMIT");
    String version = value(properties, "TURSO_SDK_KIT_VERSION");
    String soFile = value(properties, "SO_FILE");
    String soSize = value(properties, "SO_SIZE");
    String soSha256 = value(properties, "SO_SHA256");
    String soGlibcMax = value(properties, "SO_GLIBC_MAX");
    String buildImage = value(properties, "BUILD_IMAGE");
    String buildGlibc = value(properties, "BUILD_GLIBC");
    String rustVersion = value(properties, "RUST_VERSION");
    String headerSha256 = value(properties, "TURSO_HEADER_SHA256");

    if (!COMMIT.matcher(tursoCommit).matches()) {
      throw malformed("TURSO_COMMIT must be a 40-character lower-case git SHA-1");
    }
    if (!SHA256.matcher(soSha256).matches()) {
      throw malformed("SO_SHA256 must be a lower-case SHA-256 hex digest");
    }
    if (!SHA256.matcher(headerSha256).matches()) {
      throw malformed("TURSO_HEADER_SHA256 must be a lower-case SHA-256 hex digest");
    }
    if (!GLIBC.matcher(soGlibcMax).matches()) {
      throw malformed("SO_GLIBC_MAX must look like 2.35");
    }
    if (!GLIBC.matcher(buildGlibc).matches()) {
      throw malformed("BUILD_GLIBC must look like 2.36");
    }
    if (soFile.contains("/") || soFile.contains("\\") || soFile.equals(".") || soFile.equals("..")) {
      throw malformed("SO_FILE must be a plain file name");
    }

    long size;
    try {
      size = Long.parseLong(soSize);
    } catch (NumberFormatException e) {
      throw malformed("SO_SIZE must be a byte count");
    }
    if (size <= 0) {
      throw malformed("SO_SIZE must be positive");
    }

    return new NativeManifest(
        platform,
        tursoCommit,
        version,
        soFile,
        size,
        soSha256,
        soGlibcMax,
        buildImage,
        buildGlibc,
        rustVersion,
        headerSha256);
  }

  private static String value(Properties properties, String key) {
    String value = properties.getProperty(key);
    return value == null ? "" : value.trim();
  }

  private static InternalError malformed(String detail) {
    return new InternalError("Malformed native manifest " + RESOURCE_PATH + ": " + detail);
  }
}
