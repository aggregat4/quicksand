package net.aggregat4.quicksand;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.helidon.config.Config;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.staticcontent.StaticContentFeature;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.greenmail.GreenmailUtils;
import net.aggregat4.quicksand.jobs.MailFetcher;
import net.aggregat4.quicksand.repository.DatabaseMaintenance;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbActorRepository;
import net.aggregat4.quicksand.repository.DbDraftRepository;
import net.aggregat4.quicksand.repository.DbEmailRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import net.aggregat4.quicksand.repository.DraftRepository;
import net.aggregat4.quicksand.repository.EmailRepository;
import net.aggregat4.quicksand.repository.FolderRepository;
import net.aggregat4.quicksand.service.AccountService;
import net.aggregat4.quicksand.service.DraftService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.FolderService;
import net.aggregat4.quicksand.time.ApplicationClock;
import net.aggregat4.quicksand.webservice.AccountWebService;
import net.aggregat4.quicksand.webservice.AttachmentWebService;
import net.aggregat4.quicksand.webservice.EmailWebService;
import net.aggregat4.quicksand.webservice.HomeWebService;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static MailFetcher mailFetcher;
    private static GreenMail greenMail;

    public static void main(final String[] args) throws IOException {
        startServer();
    }

    static WebServer startServer() throws IOException {
        Config config = Config.create();
        boolean demoEnabled = config.get("demo.enabled").asBoolean().orElse(false);
        Clock clock = createClock(config.get("clock"));
        ApplicationClock.set(clock);

        if (demoEnabled) {
            greenMail = startDemoMailServer(config.get("demo"), clock);
        }

        // Dependency Injection and Initialisation
        DataSource ds = createDataSource(config.get("database"));
        DatabaseMaintenance.migrateDb(ds);
        DbAccountRepository accountRepository = new DbAccountRepository(ds);
        AccountService accountService = new AccountService(accountRepository);
        FolderRepository  folderRepository = new DbFolderRepository(ds);
        DbActorRepository actorRepository = new DbActorRepository(ds);
        EmailRepository messageRepository = new DbEmailRepository(ds, actorRepository);
        DraftRepository draftRepository = new DbDraftRepository(ds);
        EmailService emailService = new EmailService(messageRepository);
        DraftService draftService = new DraftService(draftRepository, messageRepository, clock);
        List<Account> accounts = loadAccounts(config, demoEnabled);
        bootstrapAccounts(accounts, accountRepository);

        boolean mailFetcherEnabled = config.get("mail_fetcher.enabled").asBoolean().orElse(demoEnabled);
        if (mailFetcherEnabled && !accounts.isEmpty()) {
            long fetchPeriodInSeconds = config.get("mail_fetcher.period_seconds").asLong().orElse(15L);
            mailFetcher = new MailFetcher(accountRepository, fetchPeriodInSeconds, folderRepository, messageRepository);
            mailFetcher.fetchNow();
            mailFetcher.start();
        } else if (mailFetcherEnabled) {
            LOGGER.info("Mail fetcher was enabled, but no accounts are configured. Skipping startup.");
        }

        FolderService folderService = new FolderService(folderRepository);

        HttpRouting.Builder routing = HttpRouting.builder()
                .register("/accounts", new AccountWebService(folderService, accountService, emailService, draftService, clock))
                .register("/emails", new EmailWebService(emailService, draftService))
                .register("/attachments", new AttachmentWebService())
                .register("/", new HomeWebService(accountService));

        WebServer server = WebServer.builder()
                .config(config.get("server"))
                .routing(routing)
                .addFeature(StaticContentFeature.create(feature -> feature
                        .name("static-assets")
                        .addClasspath(cp -> cp.location("/static/css").context("/css"))
                        .addClasspath(cp -> cp.location("/static/js").context("/js"))
                        .addClasspath(cp -> cp.location("/static/images").context("/images"))))
                .build();

        WebServer webServer = server.start();
        LOGGER.info("Web server is up at http://localhost:{}/", webServer.port(WebServer.DEFAULT_SOCKET_NAME));

        return webServer;
    }

    private static GreenMail startDemoMailServer(Config config, Clock clock) {
        int smtpPort = config.get("smtp_port").asInt().orElse(25 + 4000);
        int imapPort = config.get("imap_port").asInt().orElse(143 + 4000);
        int seedCount = config.get("seed_count").asInt().orElse(273);
        GreenMail greenMail = new GreenMail(new ServerSetup[]{
                new ServerSetup(smtpPort, null, ServerSetup.PROTOCOL_SMTP),
                new ServerSetup(imapPort, null, ServerSetup.PROTOCOL_IMAP)});
        greenMail.start();
        GreenmailUtils.deliverDemoMessages(greenMail, seedCount, clock);
        return greenMail;
    }

    private static Clock createClock(Config config) {
        return config.get("fixed_instant")
                .asString()
                .map(Instant::parse)
                .map(instant -> Clock.fixed(instant, java.time.ZoneId.systemDefault()))
                .orElse(Clock.systemDefaultZone());
    }

    /**
     * For all the accounts defined in the config, add them to the database if they are not
     * already in it.
     */
    private static void bootstrapAccounts(List<Account> accounts, DbAccountRepository accountRepository) {
        for (Account account : accounts) {
            accountRepository.createAccountIfNew(account);
        }
    }

    private static List<Account> loadAccounts(Config config, boolean demoEnabled) {
        List<Account> accounts = new ArrayList<>(config.get("accounts").asNodeList().get().stream()
                .map(Main::toAccount)
                .toList());
        if (demoEnabled) {
            accounts.add(toAccount(config.get("demo.account")));
        }
        return accounts;
    }

    private static Account toAccount(Config accountConfig) {
        return new Account(
                -1,
                accountConfig.get("name").asString().get(),
                accountConfig.get("imap_host").asString().get(),
                accountConfig.get("imap_port").asInt().get(),
                accountConfig.get("imap_username").asString().get(),
                accountConfig.get("imap_password").asString().get(),
                accountConfig.get("smtp_host").asString().get(),
                accountConfig.get("smtp_port").asInt().get(),
                accountConfig.get("smtp_username").asString().get(),
                accountConfig.get("smtp_password").asString().get());
    }

    private static DataSource createDataSource(Config config) throws IOException {
        // TODO: doing a direct sqlitedatasource since there are indications that sqlite doesn't like connection pooling
        // need to test this further and then document it
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setOpenMode(SQLiteOpenMode.READWRITE);
        sqliteConfig.setOpenMode(SQLiteOpenMode.CREATE);
        sqliteConfig.setOpenMode(SQLiteOpenMode.NOMUTEX);
        sqliteConfig.enforceForeignKeys(true);
//
//        String path = config.get("path").asString().orElseThrow(() -> {
//            throw new IllegalStateException("Require a path to the quicksand database to start");
//        });
//        Path dbPath = Paths.get(path).toAbsolutePath();
//        Files.createDirectories(dbPath.getParent());
//        if (!Files.exists(dbPath)) {
//            Files.createFile(dbPath);
//        }
//        SQLiteDataSource ds = new SQLiteDataSource(sqliteConfig);
//        ds.setUrl("jdbc:sqlite:%s".formatted(dbPath.toString()));
//        return ds;

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
        hkConfig.setDataSourceProperties(sqliteConfig.toProperties());
//        hkConfig.setMaximumPoolSize(1);
        return new HikariDataSource(hkConfig);
    }

}
