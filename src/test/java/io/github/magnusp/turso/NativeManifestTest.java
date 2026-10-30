package io.github.magnusp.turso;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.magnusp.turso.core.TursoNative;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Checks the bundled native library against the manifest that shipped with it. */
class NativeManifestTest {

  private static final String MANIFEST =
      "/io/github/magnusp/turso/native/linux-x86_64/native.properties";
  private static final String LIBRARY =
      "/io/github/magnusp/turso/native/linux-x86_64/libturso_sdk_kit.so";

  @Test
  void versionMatchesManifest() throws Exception {
    assertThat(TursoNative.version()).isEqualTo(manifest().getProperty("TURSO_SDK_KIT_VERSION"));
  }

  @Test
  void bundledLibraryMatchesManifestSizeAndHash() throws Exception {
    Properties manifest = manifest();
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long size = 0;
    try (InputStream input = NativeManifestTest.class.getResourceAsStream(LIBRARY)) {
      assertThat(input).as("bundled native library resource").isNotNull();
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
        size += read;
      }
    }

    assertThat(size).isEqualTo(Long.parseLong(manifest.getProperty("SO_SIZE")));
    assertThat(HexFormat.of().formatHex(digest.digest()))
        .isEqualTo(manifest.getProperty("SO_SHA256"));
  }

  @Test
  void syncServerImageUsesThePinnedTursoVersion() throws Exception {
    String dockerfile =
        Files.readString(Path.of("src/sync-it/resources/sync-server/Dockerfile"));
    String version = manifest().getProperty("TURSO_SDK_KIT_VERSION");

    assertThat(dockerfile)
        .as("sync server image must speak the protocol of the bundled client")
        .contains("@tursodatabase/cli-linux-x64-gnu@" + version);
  }

  private static Properties manifest() throws Exception {
    Properties properties = new Properties();
    try (InputStream input = NativeManifestTest.class.getResourceAsStream(MANIFEST)) {
      assertThat(input).as("native manifest resource").isNotNull();
      properties.load(input);
    }
    return properties;
  }
}
