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
import net.aggregat4.dblib.DbUtil;
import net.aggregat4.dblib.SchemaMigrator;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.jobs.MailFetcher;
import net.aggregat4.quicksand.migrations.QuicksandMigrations;
import net.aggregat4.quicksand.repository.AccountRepository;
import net.aggregat4.quicksand.repository.FolderRepository;
import net.aggregat4.quicksand.repository.DbMessageRepository;
import net.aggregat4.quicksand.repository.MessageRepository;
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
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

public final class  Main {

    private static MailFetcher mailFetcher;

    public static void main(final String[] args) throws IOException {
        startServer();
    }

    static Single<WebServer> startServer() throws IOException {
        LogConfig.configureRuntime();
        Config config = Config.create();

        DataSource ds = createDataSource(config.get("database"));
        migrateDb(ds);
        bootstrapAccounts(ds, config);
        AccountRepository accountRepository = new AccountRepository(ds);
        AccountService accountService = new AccountService(accountRepository);
        FolderRepository  folderRepository = new FolderRepository(ds);
        MessageRepository messageRepository = new DbMessageRepository(ds);

        // TODO: get delay period from config
        mailFetcher = new MailFetcher(accountRepository, 15, folderRepository, messageRepository);
        mailFetcher.start();

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

    /**
     * For all the accounts defined in the config, add them to the database if they are not
     * already in it.
     */
    private static void bootstrapAccounts(DataSource ds, Config config) {
        Config accountsConfig = config.get("accounts");
        List<Account> accounts = accountsConfig.asNodeList().get().stream().map(accountConfig -> new Account(
                -1,
                accountConfig.get("name").asString().get(),
                accountConfig.get("imap_host").asString().get(),
                accountConfig.get("imap_port").asInt().get(),
                accountConfig.get("imap_username").asString().get(),
                accountConfig.get("imap_password").asString().get(),
                accountConfig.get("smtp_host").asString().get(),
                accountConfig.get("smtp_port").asInt().get(),
                accountConfig.get("smtp_username").asString().get(),
                accountConfig.get("smtp_password").asString().get())).collect(Collectors.toList());
        System.out.println(accounts);
        for (Account account : accounts) {
            DbUtil.withPreparedStmtConsumer(ds, """
                    INSERT OR IGNORE INTO accounts (name, imap_host, imap_port, imap_username, imap_password, smtp_host, smtp_port, smtp_username, smtp_password) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, stmt -> {
                stmt.setString(1, account.name());
                stmt.setString(2, account.imapHost());
                stmt.setInt(3, account.imapPort());
                stmt.setString(4, account.imapUsername());
                stmt.setString(5, account.imapPassword());
                stmt.setString(6, account.smtpHost());
                stmt.setInt(7, account.smtpPort());
                stmt.setString(8, account.smtpUsername());
                stmt.setString(9, account.smtpPassword());
                int affectedRows = stmt.executeUpdate();
                if (affectedRows == 0) {
                    System.out.println("Account %s already existed".formatted(account));
                }
            });
        }
    }

    private static void migrateDb(DataSource ds) {
        try (var con = ds.getConnection()) {
            SchemaMigrator.migrate(con, new QuicksandMigrations());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
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
