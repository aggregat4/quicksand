package net.aggregat4.quicksand;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.helidon.common.LogConfig;
import io.helidon.common.reactive.Single;
import io.helidon.config.Config;
import io.helidon.media.jsonp.JsonpSupport;
import io.helidon.media.multipart.MultiPartSupport;
import io.helidon.webserver.Routing;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.staticcontent.StaticContentSupport;
import net.aggregat4.quicksand.repository.AccountRepository;
import net.aggregat4.quicksand.service.AccountService;
import net.aggregat4.quicksand.webservice.AccountWebService;
import net.aggregat4.quicksand.webservice.AttachmentWebService;
import net.aggregat4.quicksand.webservice.EmailWebService;
import net.aggregat4.quicksand.webservice.HomeWebService;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Main {

    public static void main(final String[] args) throws IOException {
        startServer();
    }

    static Single<WebServer> startServer() throws IOException {
        LogConfig.configureRuntime();
        Config config = Config.create();

        DataSource ds = createDataSource(config.get("database"));
        AccountRepository accountRepository = new AccountRepository(ds);
        AccountService accountService = new AccountService(accountRepository);

        Routing.Builder builder = Routing.builder()
                .register("/css", StaticContentSupport.create("/static/css"))
                .register("/js", StaticContentSupport.create("/static/js"))
                .register("/images", StaticContentSupport.create("/static/images"))
                .register("/accounts", new AccountWebService())
                .register("/emails", new EmailWebService())
                .register("/attachments", new AttachmentWebService())
                .register("/", new HomeWebService(accountService));

        WebServer server = WebServer.builder(builder.build())
                .config(config.get("server"))
                .addMediaSupport(JsonpSupport.create())
                .addMediaSupport(MultiPartSupport.create())
                .build();

        Single<WebServer> webserver = server.start();

        webserver
                .thenAccept(ws -> {
                    System.out.println("WEB server is up! http://localhost:" + ws.port() + "/");
                    ws.whenShutdown().thenRun(() -> System.out.println("WEB server is DOWN. Good bye!"));
                })
                .exceptionallyAccept(t -> {
                    System.err.println("Startup failed: " + t.getMessage());
                    t.printStackTrace(System.err);
                });

        return webserver;
    }

    private static DataSource createDataSource(Config config) throws IOException {
        HikariConfig hkConfig = new HikariConfig();
        String path = config.get("path").asString().orElseThrow(() -> {
            throw new IllegalStateException("Require a path to the quicksand database to start");
        });
        Path dbPath = Paths.get(path).toAbsolutePath();
        Files.createDirectories(dbPath.getParent());
        if (!Files.exists(dbPath)) {
            Files.createFile(dbPath);
        }
        hkConfig.setJdbcUrl("jdbc:sqlite:%s".formatted(dbPath.toString()));
        return new HikariDataSource(hkConfig);
    }

}
