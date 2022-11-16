package net.aggregat4.quicksand;

import io.helidon.common.LogConfig;
import io.helidon.common.reactive.Single;
import io.helidon.config.Config;
import io.helidon.media.jsonp.JsonpSupport;
import io.helidon.webserver.Routing;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.staticcontent.StaticContentSupport;
import net.aggregat4.quicksand.services.AttachmentService;
import net.aggregat4.quicksand.services.EmailService;
import net.aggregat4.quicksand.services.FolderService;
import net.aggregat4.quicksand.services.HomeService;

public final class Main {

    private Main() {
    }

    public static void main(final String[] args) {
        startServer();
    }

    // TODO: datasource configuration
    // HikariConfig config = new HikariConfig();
    // config.setJdbcUrl(jdbcUrl);
    // config.setUsername(jdbcUsername);
    // config.setPassword(jdbcPassword);
    // DataSource ds = new HikariDataSource(config);
    // try (var con = ds.getConnection()) {
    //     SchemaMigrator.migrate(con, new QuicksandMigrations());
    // } catch (SQLException e) {
    //     throw new RuntimeException(e);
    // }
    // return ds;

    static Single<WebServer> startServer() {
        LogConfig.configureRuntime();
        Config config = Config.create();

        WebServer server = WebServer.builder(createRouting(config))
                .config(config.get("server"))
                .addMediaSupport(JsonpSupport.create())
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

    private static Routing createRouting(Config config) {
        // HealthSupport health = HealthSupport.builder()
        // .addLiveness(HealthChecks.healthChecks()) // Adds a convenient set of checks
        // .build();

        Routing.Builder builder = Routing.builder()
                // .register(MetricsSupport.create()) // Metrics at "/metrics"
                // .register(health) // Health at "/health"
                .register("/css", StaticContentSupport.create("/static/css"))
                .register("/js", StaticContentSupport.create("/static/js"))
                .register("/images", StaticContentSupport.create("/static/images"))
                .register("/accounts", new FolderService())
                .register("/emails", new EmailService())
                .register("/attachments", new AttachmentService())
                .register("/", new HomeService());

        return builder.build();
    }
}
