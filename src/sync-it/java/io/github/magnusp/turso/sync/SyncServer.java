package io.github.magnusp.turso.sync;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;

/**
 * Owns the Turso sync server used by the {@code sync-it} tier.
 *
 * <p>When {@code -Dturso.sync.url} is set the harness talks to that server and never touches
 * Docker. Otherwise it starts {@code src/sync-it/resources/compose.yaml} through Testcontainers and
 * tears it down with the JVM. Tests can run against a stack started by hand with the same compose
 * file.
 */
public final class SyncServer implements AutoCloseable {

  public static final String URL_PROPERTY = "turso.sync.url";
  public static final String COMPOSE_FILE = "src/sync-it/resources/compose.yaml";
  public static final String SERVICE_NAME = "sync-server";
  public static final int SERVICE_PORT = 8080;

  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private static SyncServer shared;

  private final String url;
  private final ComposeContainer compose;
  private final HttpClient http;

  private SyncServer(String url, ComposeContainer compose) {
    this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    this.compose = compose;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /** Returns the shared server, starting the compose project on first use if needed. */
  public static synchronized SyncServer get() {
    if (shared == null) {
      shared = start();
      Runtime.getRuntime().addShutdownHook(new Thread(shared::close, "turso-sync-server-stop"));
    }
    return shared;
  }

  private static SyncServer start() {
    String external = System.getProperty(URL_PROPERTY);
    if (external != null && !external.isBlank()) {
      return new SyncServer(external.trim(), null);
    }

    File composeFile = new File(COMPOSE_FILE);
    if (!composeFile.isFile()) {
      throw new IllegalStateException(
          "Missing " + COMPOSE_FILE + " (working directory is " + new File(".").getAbsolutePath() + ")");
    }

    String project = "turso-jdbc-sync-" + UUID.randomUUID().toString().substring(0, 8);
    ComposeContainer container =
        new ComposeContainer(project, composeFile)
            // A published host port is only for manual use. Let Docker pick one so parallel
            // runs do not collide; the tests reach the service through Testcontainers.
            .withEnv("TURSO_SYNC_PORT", "0")
            .withExposedService(SERVICE_NAME, SERVICE_PORT)
            .withRemoveVolumes(true)
            .withStartupTimeout(STARTUP_TIMEOUT);
    container.start();

    String host = container.getServiceHost(SERVICE_NAME, SERVICE_PORT);
    int port = container.getServicePort(SERVICE_NAME, SERVICE_PORT);
    return new SyncServer("http://" + host + ":" + port, container);
  }

  /** Base URL of the server, without a trailing slash. */
  public String url() {
    return url;
  }

  /** True when this harness started and owns the compose project. */
  public boolean isManaged() {
    return compose != null;
  }

  /**
   * Posts a JSON request to a path on the server.
   *
   * @param path absolute path such as {@code /v2/pipeline} or {@code /db/name/v2/pipeline}
   * @param json request body
   */
  public HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url + path))
            .timeout(REQUEST_TIMEOUT)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** Sends a GET request to a path on the server. */
  public HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url + path)).timeout(REQUEST_TIMEOUT).GET().build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** Posts a single statement to the pipeline endpoint for one database. */
  public HttpResponse<String> execute(String database, String sql)
      throws IOException, InterruptedException {
    String path = database == null ? "/v2/pipeline" : "/db/" + database + "/v2/pipeline";
    String body =
        "{\"requests\":[{\"type\":\"execute\",\"stmt\":{\"sql\":"
            + quote(sql)
            + "}}]}";
    return post(path, body);
  }

  /** Polls the pipeline endpoint until the server answers or the timeout expires. */
  public void awaitHealthy(Duration timeout) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    IOException last = null;
    while (System.nanoTime() < deadline) {
      try {
        if (execute(null, "select 1").statusCode() == 200) {
          return;
        }
      } catch (IOException e) {
        last = e;
      }
      Thread.sleep(100);
    }
    throw new IOException("Sync server at " + url + " did not become healthy", last);
  }

  /** Stops the service container without removing its volume. */
  public void stopService() {
    ContainerState state = service();
    state.getDockerClient().stopContainerCmd(state.getContainerId()).exec();
  }

  /** Starts a previously stopped service container. */
  public void startService() {
    ContainerState state = service();
    state.getDockerClient().startContainerCmd(state.getContainerId()).exec();
  }

  private ContainerState service() {
    if (compose == null) {
      throw new IllegalStateException(
          "The sync server is external (" + URL_PROPERTY + "); this harness cannot stop or start it");
    }
    return compose
        .getContainerByServiceName(SERVICE_NAME)
        .orElseThrow(() -> new IllegalStateException("No " + SERVICE_NAME + " container in the compose project"));
  }

  @Override
  public void close() {
    if (compose != null) {
      compose.stop();
    }
  }

  private static String quote(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> out.append(c);
      }
    }
    return out.append('"').toString();
  }
}
