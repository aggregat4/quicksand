package net.aggregat4.quicksand;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.helidon.media.jsonp.JsonpSupport;
import io.helidon.webclient.WebClient;
import io.helidon.webclient.WebClientResponse;
import io.helidon.webserver.WebServer;
import jakarta.json.Json;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObject;

class MainTest {

    private static final JsonBuilderFactory JSON_BUILDER = Json.createBuilderFactory(Collections.emptyMap());
    private static final JsonObject TEST_JSON_OBJECT = JSON_BUILDER.createObjectBuilder()
                .add("greeting", "Hola")
                .build();

    private static WebServer webServer;
    private static WebClient webClient;

    @BeforeAll
    static void startTheServer() throws IOException {
        webServer = Main.startServer().await();

        webClient = WebClient.builder()
                .baseUri("http://localhost:" + webServer.port())
                .addMediaSupport(JsonpSupport.create())
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
    void testSimpleGreet() {
        JsonObject jsonObject = webClient.get()
                                         .path("/simple-greet")
                                         .request(JsonObject.class)
                                         .await();
        assertEquals("Hello World!", jsonObject.getString("message"));
    }
    @Test
    void testGreetings() {
        JsonObject jsonObject;
        WebClientResponse response;

        jsonObject = webClient.get()
                .path("/greet/Joe")
                .request(JsonObject.class)
                .await();
        assertEquals("Hello Joe!", jsonObject.getString("message"));

        response = webClient.put()
                .path("/greet/greeting")
                .submit(TEST_JSON_OBJECT)
                .await();
        assertEquals(204, response.status().code());

        jsonObject = webClient.get()
                .path("/greet/Joe")
                .request(JsonObject.class)
                .await();
        assertEquals("Hola Joe!", jsonObject.getString("message"));
    }
}
