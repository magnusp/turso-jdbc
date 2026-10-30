package io.github.magnusp.turso.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** Proves the compose service is reachable and speaks the endpoint the sync client uses. */
class ComposeSmokeIT {

  @Test
  void pipelineAnswersSelectOne() throws Exception {
    HttpResponse<String> response = SyncServer.get().execute(null, "select 1");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"type\":\"ok\"");
    assertThat(response.body()).contains("\"value\":\"1\"");
  }

  @Test
  void thereIsNoGetHealthEndpoint() throws Exception {
    // The server only routes POST /v2/pipeline and POST /pull-updates. A GET health check
    // would silently pass against a proxy and never reach the sync engine, so pin the 404.
    HttpResponse<String> response = SyncServer.get().get("/v2/pipeline");

    assertThat(response.statusCode()).isEqualTo(404);
  }
}
