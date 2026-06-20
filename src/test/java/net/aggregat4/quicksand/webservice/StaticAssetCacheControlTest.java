package net.aggregat4.quicksand.webservice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class StaticAssetCacheControlTest {

  @Test
  void servesHashedStaticAssetsWithImmutableCacheAndEtagRevalidation() throws Exception {
    StaticAssetRegistry registry = StaticAssetRegistry.get();
    String assetUrl = registry.url("/js/shell/app.js");
    assertTrue(assetUrl.contains("?v="));

    WebServer webServer =
        WebServer.builder()
            .port(0)
            .host("127.0.0.1")
            .routing(
                HttpRouting.builder().register("/js", new StaticAssetWebService("/js", registry)))
            .build()
            .start();
    try {
      String baseUrl = "http://localhost:" + webServer.port(WebServer.DEFAULT_SOCKET_NAME);
      HttpClient client = HttpClient.newHttpClient();

      HttpResponse<byte[]> firstResponse =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + assetUrl)).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, firstResponse.statusCode());
      assertTrue(
          firstResponse.headers().firstValue("Cache-Control").orElse("").contains("immutable"));
      String etag = firstResponse.headers().firstValue("ETag").orElseThrow();

      HttpResponse<byte[]> cachedResponse =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + assetUrl))
                  .header("If-None-Match", etag)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(304, cachedResponse.statusCode());
      assertArrayEquals(new byte[0], cachedResponse.body());

      HttpResponse<byte[]> staleHashResponse =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/js/shell/app.js?v=deadbeefdeadbeef"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(404, staleHashResponse.statusCode());
    } finally {
      webServer.stop();
    }
  }
}
