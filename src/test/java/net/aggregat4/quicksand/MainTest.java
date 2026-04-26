package net.aggregat4.quicksand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.webserver.WebServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MainTest {

  private static WebServer webServer;
  private static HttpClient httpClient;

  @BeforeAll
  static void startTheServer() throws IOException {
    Files.deleteIfExists(Path.of("target/test-db/quicksand.sqlite"));
    webServer = Main.startServer();

    httpClient = HttpClient.newHttpClient();
  }

  @AfterAll
  static void stopServer() {
    if (webServer != null) {
      webServer.stop();
    }
  }

  @Test
  void homePageRedirectsToOnlyConfiguredAccount() throws IOException, InterruptedException {
    HttpResponse<String> response = getResponse("/");
    assertEquals(302, response.statusCode());
    assertEquals("/accounts/1", response.headers().firstValue("location").orElseThrow());
  }

  @Test
  void accountPageFallsBackToDraftsWhenSyncIsDisabled() throws IOException, InterruptedException {
    String response = get("/accounts/1");
    assertTrue(response.contains("Greenmail Test Account"));
    assertTrue(response.contains("Outbox"));
    assertTrue(response.contains("Drafts"));
    assertTrue(response.contains("0 drafts"));
  }

  private static String get(String path) throws IOException, InterruptedException {
    return getResponse(path).body();
  }

  private static HttpResponse<String> getResponse(String path)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + webServer.port(WebServer.DEFAULT_SOCKET_NAME) + path))
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
