package net.aggregat4.quicksand.webservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class StaticAssetNestedPathTest {

  @Test
  void servesNestedJsPaths() throws Exception {
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
      HttpResponse<byte[]> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(baseUrl + assetUrl)).GET().build(),
                  HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      assertTrue(response.body().length > 0);
    } finally {
      webServer.stop();
    }
  }
}
