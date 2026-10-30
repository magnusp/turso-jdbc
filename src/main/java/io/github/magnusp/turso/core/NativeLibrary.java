package io.github.magnusp.turso.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Loads, verifies, and extracts one bundled native library. */
final class NativeLibrary {

  /** The identity and override properties for one library. */
  record Spec(
      String resource,
      String file,
      long size,
      String sha256,
      String glibcMax,
      String buildImage,
      String buildGlibc,
      String pathProperty,
      String directoryProperty) {}

  record Loaded(SymbolLookup lookup, Path path) {}

  static Loaded load(Spec spec) {
    Path path;
    boolean verifyHash = false;
    String explicit = System.getProperty(spec.pathProperty());
    String directory = System.getProperty(spec.directoryProperty());
    if (explicit != null && !explicit.isBlank()) {
      path = Path.of(explicit).toAbsolutePath();
      verifyHash = Boolean.parseBoolean(System.getProperty("turso.native.verifyHash", "false"));
      requireFile(spec, path);
      if (verifyHash) {
        verify(spec, path);
      }
    } else if (directory != null && !directory.isBlank()) {
      path = Path.of(directory).resolve(spec.file()).toAbsolutePath();
      verifyHash = Boolean.parseBoolean(System.getProperty("turso.native.verifyHash", "false"));
      requireFile(spec, path);
      if (verifyHash) {
        verify(spec, path);
      }
    } else {
      checkPlatform();
      path = extract(spec);
    }
    return new Loaded(libraryLookup(spec, path), path);
  }

  private static void requireFile(Spec spec, Path path) {
    if (!Files.isRegularFile(path)) {
      throw new InternalError(
          "Native library not found at "
              + path
              + ". Set -D"
              + spec.pathProperty()
              + " to the .so file, -D"
              + spec.directoryProperty()
              + " to its directory, or omit both to use the library bundled in the jar.");
    }
  }

  private static void verify(Spec spec, Path path) {
    long size;
    String hash;
    try {
      size = Files.size(path);
      hash = sha256(path);
    } catch (IOException e) {
      throw new InternalError("Unable to read native library " + path, e);
    }
    if (size != spec.size() || !hash.equals(spec.sha256())) {
      throw new InternalError(
          "Native library "
              + path
              + " does not match "
              + NativeManifest.RESOURCE_PATH
              + ": expected size "
              + spec.size()
              + " and SHA-256 "
              + spec.sha256()
              + ", got size "
              + size
              + " and SHA-256 "
              + hash
              + ".");
    }
  }

  private static String sha256(Path path) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new InternalError("SHA-256 is not available", e);
    }
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static SymbolLookup libraryLookup(Spec spec, Path path) {
    try {
      return SymbolLookup.libraryLookup(path, Arena.global());
    } catch (IllegalArgumentException | SecurityException | UnsatisfiedLinkError e) {
      throw new InternalError(
          "Unable to load "
              + spec.file()
              + " from "
              + path
              + ": this build requires glibc <= "
              + spec.glibcMax()
              + " (SO_GLIBC_MAX) and was built on "
              + spec.buildImage()
              + " (BUILD_IMAGE, glibc "
              + spec.buildGlibc()
              + "). Check `ldd --version` and use a host with a compatible glibc, or rebuild the"
              + " library with scripts/build-native.sh.",
          e);
    }
  }

  private static Path extract(Spec spec) {
    try (InputStream input = NativeLibrary.class.getResourceAsStream(spec.resource())) {
      if (input == null) {
        throw new InternalError(
            "Missing native library resource "
                + spec.resource()
                + ". Build it with scripts/build-native.sh or set -D"
                + spec.pathProperty()
                + ".");
      }
      String prefix = spec.file().replace(".so", "") + "-";
      Path temp = Files.createTempFile(prefix, ".so");
      boolean complete = false;
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (OutputStream output = Files.newOutputStream(temp)) {
          byte[] buffer = new byte[64 * 1024];
          int read;
          while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
            size += read;
          }
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        if (size != spec.size() || !hash.equals(spec.sha256())) {
          throw new InternalError(
              "Native library resource "
                  + spec.resource()
                  + " does not match "
                  + NativeManifest.RESOURCE_PATH
                  + ": expected size "
                  + spec.size()
                  + " and SHA-256 "
                  + spec.sha256()
                  + ", got size "
                  + size
                  + " and SHA-256 "
                  + hash
                  + ". The jar is corrupt or was built with a mismatched manifest.");
        }
        temp.toFile().deleteOnExit();
        temp.toFile().setReadable(true, true);
        temp.toFile().setWritable(true, true);
        temp.toFile().setExecutable(true, true);
        complete = true;
        return temp.toAbsolutePath();
      } finally {
        if (!complete) {
          Files.deleteIfExists(temp);
        }
      }
    } catch (IOException e) {
      throw new InternalError("Unable to extract " + spec.file(), e);
    } catch (NoSuchAlgorithmException e) {
      throw new InternalError("SHA-256 is not available", e);
    }
  }

  private static void checkPlatform() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    boolean linux = os.contains("linux");
    boolean x86 = arch.contains("x86_64") || arch.contains("amd64");
    if (!linux || !x86) {
      throw new UnsupportedOperationException(
          "This build of turso-jdbc ships only Linux x86_64. Detected " + os + "/" + arch + ".");
    }
  }

  private NativeLibrary() {}
}
