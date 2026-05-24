package net.aggregat4.quicksand;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.helidon.config.Config;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.staticcontent.StaticContentFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.jobs.MailFetcher;
import net.aggregat4.quicksand.jobs.MailSender;
import net.aggregat4.quicksand.jobs.MailboxActionSync;
import net.aggregat4.quicksand.repository.AccountFolderMappingRepository;
import net.aggregat4.quicksand.repository.AccountRepository;
import net.aggregat4.quicksand.repository.AttachmentRepository;
import net.aggregat4.quicksand.repository.DatabaseMaintenance;
import net.aggregat4.quicksand.repository.DbAccountFolderMappingRepository;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbDraftRepository;
import net.aggregat4.quicksand.repository.DbEmailRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import net.aggregat4.quicksand.repository.DbOutboundMessageRepository;
import net.aggregat4.quicksand.repository.DraftRepository;
import net.aggregat4.quicksand.repository.EmailRepository;
import net.aggregat4.quicksand.repository.FolderRepository;
import net.aggregat4.quicksand.repository.OutboundMessageRepository;
import net.aggregat4.quicksand.security.AccountCredentialCipher;
import net.aggregat4.quicksand.service.AccountFolderMappingService;
import net.aggregat4.quicksand.service.AccountService;
import net.aggregat4.quicksand.service.AttachmentService;
import net.aggregat4.quicksand.service.DraftService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.FolderService;
import net.aggregat4.quicksand.service.MailboxSyncRecoveryService;
import net.aggregat4.quicksand.service.MailboxUpdateBroadcaster;
import net.aggregat4.quicksand.service.NotificationService;
import net.aggregat4.quicksand.service.OutboundMessageService;
import net.aggregat4.quicksand.time.ApplicationClock;
import net.aggregat4.quicksand.webservice.AccountWebService;
import net.aggregat4.quicksand.webservice.AttachmentWebService;
import net.aggregat4.quicksand.webservice.EmailWebService;
import net.aggregat4.quicksand.webservice.HomeWebService;
import net.aggregat4.quicksand.webservice.OutboxWebService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

public final class Main {
  private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

  private static MailFetcher mailFetcher;
  private static MailSender mailSender;
  private static MailboxActionSync mailboxActionSync;
  private static boolean demoMailServerStarted;
  private static volatile boolean shutdownHookRegistered;

  public static void main(final String[] args) throws IOException {
    startServer();
  }

  static WebServer startServer() throws IOException {
    Config config = Config.create();
    boolean demoEnabled = config.get("demo.enabled").asBoolean().orElse(false);
    Clock clock = createClock(config.get("clock"));
    ApplicationClock.set(clock);

    if (demoEnabled) {
      startDemoMailServer(config.get("demo"), clock);
      demoMailServerStarted = true;
    }

    // Dependency Injection and Initialisation
    DataSource ds = createDataSource(config.get("database"));
    DatabaseMaintenance.migrateDb(ds);
    AccountCredentialCipher credentialCipher = AccountCredentialCipher.load(config);
    DbAccountRepository accountRepository = new DbAccountRepository(ds, credentialCipher);
    accountRepository.reencryptLegacyCredentials();
    AccountService accountService = new AccountService(accountRepository);
    FolderRepository folderRepository = new DbFolderRepository(ds);
    AccountFolderMappingRepository accountFolderMappingRepository =
        new DbAccountFolderMappingRepository(ds);
    DbAttachmentRepository dbAttachmentRepository = new DbAttachmentRepository(ds);
    EmailRepository messageRepository = new DbEmailRepository(ds, dbAttachmentRepository);
    DbDraftRepository dbDraftRepository = new DbDraftRepository(ds);
    DraftRepository draftRepository = dbDraftRepository;
    AttachmentRepository attachmentRepository = dbAttachmentRepository;
    OutboundMessageRepository outboundMessageRepository = new DbOutboundMessageRepository(ds);
    AttachmentService attachmentService = new AttachmentService(attachmentRepository);
    EmailService emailService = new EmailService(messageRepository);
    long draftSyncDebounceSeconds =
        config.get("mailbox_action_sync.draft_debounce_seconds").asLong().orElse(5L);
    DraftService draftService =
        new DraftService(
            draftRepository, messageRepository, attachmentService, draftSyncDebounceSeconds, clock);
    OutboundMessageService outboundMessageService =
        new OutboundMessageService(
            ds,
            accountRepository,
            draftRepository,
            attachmentRepository,
            outboundMessageRepository,
            messageRepository,
            clock);
    List<Account> accounts = loadAccounts(config, demoEnabled);
    bootstrapAccounts(accounts, accountRepository);

    AccountFolderMappingService accountFolderMappingService =
        new AccountFolderMappingService(
            accountFolderMappingRepository, folderRepository, accountRepository);
    MailboxUpdateBroadcaster mailboxUpdateBroadcaster = new MailboxUpdateBroadcaster();

    boolean mailFetcherEnabled = config.get("mail_fetcher.enabled").asBoolean().orElse(demoEnabled);
    if (mailFetcherEnabled && !accounts.isEmpty()) {
      long fetchPeriodInSeconds = config.get("mail_fetcher.period_seconds").asLong().orElse(15L);
      boolean idleEnabled = config.get("mail_fetcher.idle_enabled").asBoolean().orElse(false);
      mailFetcher =
          new MailFetcher(
              accountRepository,
              fetchPeriodInSeconds,
              folderRepository,
              messageRepository,
              accountFolderMappingService,
              idleEnabled,
              mailboxUpdateBroadcaster);
      mailFetcher.fetchNow();
      mailFetcher.start();
    } else if (mailFetcherEnabled) {
      LOGGER.info("Mail fetcher was enabled, but no accounts are configured. Skipping startup.");
    }

    boolean mailSenderEnabled = config.get("mail_sender.enabled").asBoolean().orElse(demoEnabled);
    if (mailSenderEnabled && !accounts.isEmpty()) {
      long sendPeriodInSeconds = config.get("mail_sender.period_seconds").asLong().orElse(15L);
      int maxAttempts = config.get("mail_sender.max_attempts").asInt().orElse(3);
      long retryDelaySeconds = config.get("mail_sender.retry_delay_seconds").asLong().orElse(60L);
      mailSender =
          new MailSender(
              accountRepository,
              outboundMessageRepository,
              attachmentRepository,
              messageRepository,
              clock,
              sendPeriodInSeconds,
              maxAttempts,
              retryDelaySeconds);
      mailSender.start();
    } else if (mailSenderEnabled) {
      LOGGER.info("Mail sender was enabled, but no accounts are configured. Skipping startup.");
    }

    boolean mailboxActionSyncEnabled =
        config.get("mailbox_action_sync.enabled").asBoolean().orElse(demoEnabled);
    if (mailboxActionSyncEnabled && !accounts.isEmpty()) {
      long syncPeriodInSeconds =
          config.get("mailbox_action_sync.period_seconds").asLong().orElse(15L);
      long retryDelaySeconds =
          config.get("mailbox_action_sync.retry_delay_seconds").asLong().orElse(60L);
      mailboxActionSync =
          new MailboxActionSync(
              accountRepository,
              messageRepository,
              outboundMessageRepository,
              attachmentRepository,
              draftRepository,
              folderRepository,
              clock,
              syncPeriodInSeconds,
              retryDelaySeconds);
      mailboxActionSync.syncNow();
      mailboxActionSync.start();
    } else if (mailboxActionSyncEnabled) {
      LOGGER.info(
          "Mailbox action sync was enabled, but no accounts are configured. Skipping startup.");
    } else {
      LOGGER.info(
          "Mailbox action sync is disabled; queued mailbox actions will remain pending until it"
              + " is enabled.");
    }

    FolderService folderService = new FolderService(folderRepository);
    Runnable backgroundSyncTrigger =
        () -> {
          if (mailFetcher != null) {
            mailFetcher.fetchNow();
          }
          if (mailboxActionSync != null) {
            mailboxActionSync.syncNow();
          }
        };
    MailboxSyncRecoveryService mailboxSyncRecoveryService =
        new MailboxSyncRecoveryService(
            messageRepository, accountFolderMappingRepository, backgroundSyncTrigger, clock);
    NotificationService notificationService =
        new NotificationService(folderRepository, messageRepository, clock);

    HttpRouting.Builder routing =
        HttpRouting.builder()
            .register(
                "/accounts",
                new AccountWebService(
                    folderService,
                    accountService,
                    accountFolderMappingService,
                    emailService,
                    draftService,
                    outboundMessageService,
                    mailboxSyncRecoveryService,
                    notificationService,
                    mailboxUpdateBroadcaster,
                    clock))
            .register(
                "/emails",
                new EmailWebService(
                    emailService,
                    draftService,
                    attachmentService,
                    outboundMessageService,
                    accountFolderMappingService))
            .register("/outbox", new OutboxWebService(outboundMessageService))
            .register("/attachments", new AttachmentWebService(attachmentService))
            .register("/", new HomeWebService(accountService));

    WebServer server =
        WebServer.builder()
            .config(config.get("server"))
            .routing(routing)
            .addFeature(
                StaticContentFeature.create(
                    feature ->
                        feature
                            .name("static-assets")
                            .addClasspath(cp -> cp.location("/static/css").context("/css"))
                            .addClasspath(cp -> cp.location("/static/js").context("/js"))
                            .addClasspath(cp -> cp.location("/static/images").context("/images"))))
            .build();

    WebServer webServer = server.start();
    LOGGER.info(
        "Web server is up at http://localhost:{}/", webServer.port(WebServer.DEFAULT_SOCKET_NAME));

    registerShutdownHook();
    return webServer;
  }

  private static void registerShutdownHook() {
    if (shutdownHookRegistered) {
      return;
    }
    synchronized (Main.class) {
      if (shutdownHookRegistered) {
        return;
      }
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    LOGGER.info("Shutting down background jobs");
                    if (mailboxActionSync != null) {
                      mailboxActionSync.stop();
                    }
                    if (mailSender != null) {
                      mailSender.stop();
                    }
                    if (mailFetcher != null) {
                      mailFetcher.stop();
                    }
                    if (demoMailServerStarted) {
                      stopDemoMailServer();
                    }
                  },
                  "quicksand-shutdown"));
      shutdownHookRegistered = true;
    }
  }

  private static void startDemoMailServer(Config demoConfig, Clock clock) {
    try {
      Class<?> demoMailServer = Class.forName("net.aggregat4.quicksand.demo.DemoMailServer");
      demoMailServer.getMethod("start", Config.class, Clock.class).invoke(null, demoConfig, clock);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Demo mode requires a build with the demo profile (embedded GreenMail).", e);
    }
  }

  private static void stopDemoMailServer() {
    try {
      Class<?> demoMailServer = Class.forName("net.aggregat4.quicksand.demo.DemoMailServer");
      demoMailServer.getMethod("stop").invoke(null);
    } catch (ReflectiveOperationException e) {
      LOGGER.warn("Failed to stop embedded demo mail server", e);
    }
  }

  private static Clock createClock(Config config) {
    return config
        .get("fixed_instant")
        .asString()
        .map(Instant::parse)
        .map(instant -> Clock.fixed(instant, ApplicationClock.zone()))
        .orElse(Clock.system(ApplicationClock.zone()));
  }

  /**
   * For all the accounts defined in the config, add them to the database if they are not already in
   * it.
   */
  private static void bootstrapAccounts(
      List<Account> accounts, AccountRepository accountRepository) {
    for (Account account : accounts) {
      accountRepository.createAccountIfNew(account);
    }
  }

  private static List<Account> loadAccounts(Config config, boolean demoEnabled) {
    List<Account> accounts =
        new ArrayList<>(
            config.get("accounts").asNodeList().get().stream().map(Main::toAccount).toList());
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
    // TODO: doing a direct sqlitedatasource since there are indications that sqlite doesn't like
    // connection pooling
    // need to test this further and then document it
    SQLiteConfig sqliteConfig = new SQLiteConfig();
    sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
    sqliteConfig.setOpenMode(SQLiteOpenMode.READWRITE);
    sqliteConfig.setOpenMode(SQLiteOpenMode.CREATE);
    sqliteConfig.setOpenMode(SQLiteOpenMode.NOMUTEX);
    sqliteConfig.enforceForeignKeys(true);
    //
    //        String path = config.get("path").asString().orElseThrow(() -> {
    //            throw new IllegalStateException("Require a path to the quicksand database to
    // start");
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
    String path =
        config
            .get("path")
            .asString()
            .orElseThrow(
                () -> {
                  throw new IllegalStateException(
                      "Require a path to the quicksand database to start");
                });
    Path dbPath = Paths.get(path).toAbsolutePath();
    Files.createDirectories(dbPath.getParent());
    if (!Files.exists(dbPath)) {
      Files.createFile(dbPath);
    }

    hkConfig.setJdbcUrl("jdbc:sqlite:%s".formatted(dbPath.toString()));
    hkConfig.setDataSourceProperties(sqliteConfig.toProperties());
    // SQLite serializes writers, but repository code may open nested connections (e.g. load
    // actors while iterating messages). Keep the pool tiny, not single-connection.
    hkConfig.setMaximumPoolSize(2);
    hkConfig.setMinimumIdle(1);
    return new HikariDataSource(hkConfig);
  }
}
