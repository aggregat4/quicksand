package net.aggregat4.quicksand;

import io.helidon.webclient.WebClient;
import io.helidon.webserver.WebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    private static WebServer webServer;
    private static WebClient webClient;

    @BeforeAll
    static void startTheServer() throws IOException {
        Files.deleteIfExists(Path.of("target/test-db/quicksand.sqlite"));
        webServer = Main.startServer().await();

        webClient = WebClient.builder()
                .baseUri("http://localhost:" + webServer.port())
                .build();
    }

    @AfterAll
    static void stopServer() throws ExecutionException, InterruptedException, TimeoutException {
        if (webServer != null) {
            webServer.shutdown()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void homePageRenders() {
        String response = webClient.get()
                .path("/")
                .request(String.class)
                .await();
        assertTrue(response.contains("Quicksand E-Mail Home"));
        assertTrue(response.contains("Hello World!"));
    }

    @Test
    void accountPageRendersWithoutFoldersWhenSyncIsDisabled() {
        String response = webClient.get()
                .path("/accounts/1")
                .request(String.class)
                .await();
        assertTrue(response.contains("Greenmail Test Account"));
        assertTrue(response.contains("This account has no folders."));
    }
}
