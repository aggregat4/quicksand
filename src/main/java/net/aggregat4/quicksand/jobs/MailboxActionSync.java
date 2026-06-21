package net.aggregat4.quicksand.jobs;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.MessageIDTerm;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.MailboxActionExecutionState;
import net.aggregat4.quicksand.domain.MailboxActionQueueRow;
import net.aggregat4.quicksand.domain.MailboxActionType;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.OutboundMessage;
import net.aggregat4.quicksand.domain.StoredAttachment;
import net.aggregat4.quicksand.repository.AccountRepository;
import net.aggregat4.quicksand.repository.AttachmentRepository;
import net.aggregat4.quicksand.repository.DraftRepository;
import net.aggregat4.quicksand.repository.EmailRepository;
import net.aggregat4.quicksand.repository.FolderRepository;
import net.aggregat4.quicksand.repository.OutboundMessageRepository;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MailboxActionSync {
  private static final Logger LOGGER = LoggerFactory.getLogger(MailboxActionSync.class);
  private static final long INITIAL_DELAY_SECONDS = 0;
  private static final int BATCH_SIZE = 50;
  private static final int READ_STATE_BATCH_SIZE = 500;
  private static final int MAX_READ_STATE_BATCHES_PER_SYNC = 20;

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private ScheduledFuture<?> scheduledTask;
  private final AccountRepository accountRepository;
  private final EmailRepository emailRepository;
  private final OutboundMessageRepository outboundMessageRepository;
  private final AttachmentRepository attachmentRepository;
  private final DraftRepository draftRepository;
  private final FolderRepository folderRepository;
  private final Clock clock;
  private final long syncPeriodInSeconds;
  private final long retryDelaySeconds;
  private final AccountSyncCoordinator syncCoordinator;

  public MailboxActionSync(
      AccountRepository accountRepository,
      EmailRepository emailRepository,
      OutboundMessageRepository outboundMessageRepository,
      AttachmentRepository attachmentRepository,
      DraftRepository draftRepository,
      FolderRepository folderRepository,
      Clock clock,
      long syncPeriodInSeconds,
      long retryDelaySeconds) {
    this(
        accountRepository,
        emailRepository,
        outboundMessageRepository,
        attachmentRepository,
        draftRepository,
        folderRepository,
        clock,
        syncPeriodInSeconds,
        retryDelaySeconds,
        new AccountSyncCoordinator());
  }

  public MailboxActionSync(
      AccountRepository accountRepository,
      EmailRepository emailRepository,
      OutboundMessageRepository outboundMessageRepository,
      AttachmentRepository attachmentRepository,
      DraftRepository draftRepository,
      FolderRepository folderRepository,
      Clock clock,
      long syncPeriodInSeconds,
      long retryDelaySeconds,
      AccountSyncCoordinator syncCoordinator) {
    this.accountRepository = accountRepository;
    this.emailRepository = emailRepository;
    this.outboundMessageRepository = outboundMessageRepository;
    this.attachmentRepository = attachmentRepository;
    this.draftRepository = draftRepository;
    this.folderRepository = folderRepository;
    this.clock = clock;
    this.syncPeriodInSeconds = syncPeriodInSeconds;
    this.retryDelaySeconds = retryDelaySeconds;
    this.syncCoordinator = syncCoordinator;
  }

  public void start() {
    scheduledTask =
        scheduler.scheduleWithFixedDelay(
            this::syncDueActions, INITIAL_DELAY_SECONDS, syncPeriodInSeconds, TimeUnit.SECONDS);
  }

  public void syncNow() {
    syncDueActions();
  }

  public void stop() {
    if (scheduledTask != null) {
      scheduledTask.cancel(false);
    }
    scheduler.shutdown();
  }

  void syncDueActions() {
    ZonedDateTime now = ZonedDateTime.now(clock);
    emailRepository.purgeStaleMailboxActionRows(now);
    for (Account account : accountRepository.getAccounts()) {
      syncAccount(account.id());
    }
  }

  public void syncAccount(int accountId) {
    syncCoordinator.run(
        accountId,
        () -> {
          ZonedDateTime now = ZonedDateTime.now(clock);
          syncDueReadStateActions(accountId, now);
          syncDueOtherActions(accountId, now);
        });
  }

  private void syncDueReadStateActions(int accountId, ZonedDateTime now) {
    for (int batchIndex = 0; batchIndex < MAX_READ_STATE_BATCHES_PER_SYNC; batchIndex++) {
      List<MailboxActionQueueRow> actions =
          emailRepository.claimDueReadStateActions(accountId, now, READ_STATE_BATCH_SIZE);
      if (actions.isEmpty()) {
        return;
      }
      syncCoordinator.run(actions.getFirst().accountId(), () -> applyReadStateBatch(actions, now));
    }
  }

  private void syncDueOtherActions(int accountId, ZonedDateTime now) {
    List<MailboxActionQueueRow> actions =
        emailRepository.claimDueMailboxActions(accountId, now, BATCH_SIZE);
    for (MailboxActionQueueRow action : actions) {
      syncCoordinator.run(action.accountId(), () -> applyClaimedAction(action));
    }
  }

  private void applyClaimedAction(MailboxActionQueueRow action) {
    try {
      boolean completionPersisted = applyAction(action);
      if (!completionPersisted) {
        emailRepository.markMailboxActionSucceeded(action.id(), ZonedDateTime.now(clock));
      }
    } catch (MailboxActionConflictException e) {
      emailRepository.markMailboxActionConflict(
          action.id(), abbreviateError(e), ZonedDateTime.now(clock));
    } catch (MailboxActionPermanentException e) {
      emailRepository.markMailboxActionPermanentFailure(
          action.id(), abbreviateError(e), ZonedDateTime.now(clock));
    } catch (MessagingException | RuntimeException e) {
      LOGGER.warn("Failed to sync mailbox action {}", action.id(), e);
      ZonedDateTime retryAt =
          ZonedDateTime.now(clock).plusSeconds(backoffSeconds(action.attemptCount()));
      emailRepository.markMailboxActionRetry(
          action.id(), abbreviateError(e), retryAt, ZonedDateTime.now(clock));
    }
  }

  private void applyReadStateBatch(List<MailboxActionQueueRow> actions, ZonedDateTime now) {
    List<MailboxActionQueueRow> invalidActions = new ArrayList<>();
    List<MailboxActionQueueRow> validActions = new ArrayList<>();
    for (MailboxActionQueueRow action : actions) {
      if (action.sourceRemoteName() == null || action.sourceUid() == null) {
        invalidActions.add(action);
      } else {
        validActions.add(action);
      }
    }
    for (MailboxActionQueueRow action : invalidActions) {
      emailRepository.markMailboxActionConflict(
          action.id(), "Queued action is missing source mailbox identity", now);
    }
    if (validActions.isEmpty()) {
      return;
    }

    try {
      ReadStateBatchOutcome outcome = applyReadStateBatchToImap(validActions);
      for (MailboxActionQueueRow action : outcome.succeeded()) {
        emailRepository.markMailboxActionSucceeded(action.id(), now);
      }
      for (ReadStateBatchConflict conflict : outcome.conflicts()) {
        emailRepository.markMailboxActionConflict(conflict.action().id(), conflict.error(), now);
      }
    } catch (MailboxActionConflictException e) {
      String error = abbreviateError(e);
      for (MailboxActionQueueRow action : validActions) {
        emailRepository.markMailboxActionConflict(action.id(), error, now);
      }
    } catch (MessagingException | RuntimeException e) {
      LOGGER.warn("Failed to sync read-state batch of {} actions", validActions.size(), e);
      ZonedDateTime retryAt =
          now.plusSeconds(backoffSeconds(validActions.getFirst().attemptCount()));
      String error = abbreviateError(e);
      for (MailboxActionQueueRow action : validActions) {
        emailRepository.markMailboxActionRetry(action.id(), error, retryAt, now);
      }
    }
  }

  private boolean applyAction(MailboxActionQueueRow action) throws MessagingException {
    if (MailboxActionType.MOVE_LIKE.contains(action.actionType())) {
      return applyMoveLikeAction(action);
    }
    if (action.actionType() == MailboxActionType.APPEND_SENT) {
      applyAppendSentAction(action);
      return false;
    }
    if (action.actionType() == MailboxActionType.UPSERT_DRAFT) {
      applyUpsertDraftAction(action);
      return false;
    }
    if (action.actionType() == MailboxActionType.DELETE_DRAFT) {
      applyDeleteDraftAction(action);
      return false;
    }
    throw new IllegalArgumentException("Unsupported mailbox action type " + action.actionType());
  }

  private void applyUpsertDraftAction(MailboxActionQueueRow action) throws MessagingException {
    if (action.targetRemoteName() == null || action.payloadJson() == null) {
      throw new MailboxActionConflictException(
          "Queued draft upsert is missing target mailbox or draft identity");
    }
    int draftId = Integer.parseInt(action.payloadJson());
    Draft draft =
        draftRepository
            .findById(draftId)
            .orElseThrow(
                () ->
                    new MailboxActionConflictException(
                        "Draft %s no longer exists".formatted(draftId)));
    if (draft.accountId() != action.accountId()) {
      throw new MailboxActionConflictException("Draft does not belong to this account");
    }
    Account account = accountRepository.getAccount(action.accountId());
    MimeMessage mimeMessage;
    try {
      mimeMessage = DraftMimeMessageBuilder.build(account, draft);
    } catch (java.io.UnsupportedEncodingException e) {
      throw new MessagingException("Failed to build draft for IMAP append", e);
    }

    try (Store store = createConnectedStore(account)) {
      Folder targetFolder = store.getFolder(action.targetRemoteName());
      if (!targetFolder.exists()) {
        throw new MailboxActionConflictException("Drafts mailbox does not exist on server");
      }
      if (!(targetFolder instanceof IMAPFolder imapFolder)) {
        throw new MessagingException("Drafts folder is not an IMAP folder");
      }
      imapFolder.open(Folder.READ_WRITE);
      try {
        if (draft.remoteImapUid().isPresent()) {
          deleteRemoteMessage(imapFolder, draft.remoteImapUid().get());
        }
        AppendUID[] appended = imapFolder.appendUIDMessages(new Message[] {mimeMessage});
        if (appended == null || appended.length != 1 || appended[0] == null) {
          throw new MessagingException("IMAP APPEND did not return APPENDUID");
        }
        draftRepository.updateRemoteIdentity(draftId, appended[0].uid, imapFolder.getUIDValidity());
      } finally {
        imapFolder.close(false);
      }
    }
  }

  private void applyDeleteDraftAction(MailboxActionQueueRow action) throws MessagingException {
    if (action.sourceRemoteName() == null || action.sourceUid() == null) {
      return;
    }
    Account account = accountRepository.getAccount(action.accountId());
    try (Store store = createConnectedStore(account)) {
      IMAPFolder imapFolder = openSourceFolder(store, action);
      try {
        deleteRemoteMessage(imapFolder, action.sourceUid());
      } finally {
        imapFolder.close(false);
      }
    }
  }

  private static void deleteRemoteMessage(IMAPFolder imapFolder, long uid)
      throws MessagingException {
    Message message = imapFolder.getMessageByUID(uid);
    if (message == null) {
      return;
    }
    message.setFlag(Flags.Flag.DELETED, true);
    imapFolder.expunge();
  }

  private void applyAppendSentAction(MailboxActionQueueRow action) throws MessagingException {
    if (action.targetRemoteName() == null || action.payloadJson() == null) {
      throw new MailboxActionConflictException(
          "Queued sent append is missing target mailbox or outbound message identity");
    }
    int outboundMessageId = Integer.parseInt(action.payloadJson());
    OutboundMessage outboundMessage =
        outboundMessageRepository
            .findById(outboundMessageId)
            .orElseThrow(
                () ->
                    new MailboxActionConflictException(
                        "Outbound message %s no longer exists".formatted(outboundMessageId)));
    if (outboundMessage.accountId() != action.accountId()) {
      throw new MailboxActionConflictException("Outbound message does not belong to this account");
    }
    Account account = accountRepository.getAccount(action.accountId());
    List<StoredAttachment> attachments =
        attachmentRepository.findStoredByOutboundMessageId(outboundMessageId);
    MimeMessage mimeMessage;
    try {
      mimeMessage = OutboundMimeMessageBuilder.build(account, outboundMessage, attachments);
    } catch (java.io.UnsupportedEncodingException e) {
      throw new MessagingException("Failed to build sent message for IMAP append", e);
    }

    try (Store store = createConnectedStore(account)) {
      Folder targetFolder = store.getFolder(action.targetRemoteName());
      if (!targetFolder.exists()) {
        throw new MailboxActionConflictException("Sent mailbox does not exist on server");
      }
      if (!(targetFolder instanceof IMAPFolder imapFolder)) {
        throw new MessagingException("Sent folder is not an IMAP folder");
      }
      imapFolder.open(Folder.READ_WRITE);
      try {
        AppendUID[] appended = imapFolder.appendUIDMessages(new Message[] {mimeMessage});
        if (appended != null && appended.length == 1 && appended[0] != null) {
          LOGGER.debug(
              "Appended sent message {} to {} with UID {}",
              outboundMessageId,
              action.targetRemoteName(),
              appended[0].uid);
        }
      } finally {
        imapFolder.close(false);
      }
      if (action.targetFolderId() != null) {
        NamedFolder localSentFolder = folderRepository.getFolder(action.targetFolderId());
        ImapFolderSyncEngine.syncFolder(
            action.accountId(),
            localSentFolder,
            imapFolder,
            store,
            folderRepository,
            emailRepository);
      }
    }
  }

  private ReadStateBatchOutcome applyReadStateBatchToImap(List<MailboxActionQueueRow> actions)
      throws MessagingException {
    MailboxActionQueueRow first = actions.getFirst();
    Account account = accountRepository.getAccount(first.accountId());
    boolean seen = first.actionType() == MailboxActionType.MARK_READ;

    try (Store store = createConnectedStore(account)) {
      IMAPFolder imapFolder = openSourceFolder(store, first);
      try {
        long[] uids = new long[actions.size()];
        for (int index = 0; index < actions.size(); index++) {
          uids[index] = actions.get(index).sourceUid();
        }
        Message[] messages = imapFolder.getMessagesByUID(uids);

        List<MailboxActionQueueRow> succeeded = new ArrayList<>();
        List<ReadStateBatchConflict> conflicts = new ArrayList<>();
        List<Message> messagesToUpdate = new ArrayList<>();
        for (int index = 0; index < messages.length; index++) {
          MailboxActionQueueRow action = actions.get(index);
          if (messages[index] == null) {
            conflicts.add(new ReadStateBatchConflict(action, "Source UID no longer exists"));
          } else {
            messagesToUpdate.add(messages[index]);
            succeeded.add(action);
          }
        }
        if (!messagesToUpdate.isEmpty()) {
          imapFolder.setFlags(
              messagesToUpdate.toArray(Message[]::new), new Flags(Flags.Flag.SEEN), seen);
        }
        return new ReadStateBatchOutcome(succeeded, conflicts);
      } finally {
        imapFolder.close(false);
      }
    }
  }

  private record ReadStateBatchOutcome(
      List<MailboxActionQueueRow> succeeded, List<ReadStateBatchConflict> conflicts) {}

  private record ReadStateBatchConflict(MailboxActionQueueRow action, String error) {}

  private boolean applyMoveLikeAction(MailboxActionQueueRow action) throws MessagingException {
    if (action.sourceRemoteName() == null
        || action.sourceUid() == null
        || action.targetRemoteName() == null) {
      throw new MailboxActionConflictException(
          "Queued move action is missing source or target mailbox identity");
    }
    if (action.sourceRemoteName().equals(action.targetRemoteName())) {
      return false;
    }
    Account account = accountRepository.getAccount(action.accountId());
    try (Store store = createConnectedStore(account)) {
      if (!(store instanceof IMAPStore imapStore) || !imapStore.hasCapability("MOVE")) {
        throw new MailboxActionPermanentException("Server does not support UID MOVE");
      }
      IMAPFolder sourceFolder = openSourceFolder(store, action);
      try {
        Folder targetFolder = store.getFolder(action.targetRemoteName());
        if (!targetFolder.exists()) {
          throw new MailboxActionConflictException("Target mailbox does not exist on server");
        }
        if (!(targetFolder instanceof IMAPFolder imapTargetFolder)) {
          throw new MessagingException("Target folder is not an IMAP folder");
        }
        Message message = sourceFolder.getMessageByUID(action.sourceUid());
        if (message == null) {
          if (action.executionState() == MailboxActionExecutionState.ATTEMPTED_UNKNOWN
              && recoverMoveFromTarget(action, imapTargetFolder)) {
            return true;
          }
          throw new MailboxActionConflictException("Source UID no longer exists");
        }
        emailRepository.markMailboxActionAttemptedUnknown(action.id(), ZonedDateTime.now(clock));
        AppendUID[] moved = sourceFolder.moveUIDMessages(new Message[] {message}, targetFolder);
        if (moved == null || moved.length != 1 || moved[0] == null) {
          throw new MessagingException("UID MOVE did not return a target UID");
        }
        emailRepository.confirmMailboxMoveApplied(
            action.id(),
            action.messageId(),
            action.targetFolderId(),
            moved[0].uidvalidity,
            moved[0].uid,
            ZonedDateTime.now(clock));
        return true;
      } finally {
        sourceFolder.close(false);
      }
    }
  }

  private boolean recoverMoveFromTarget(MailboxActionQueueRow action, IMAPFolder targetFolder)
      throws MessagingException {
    if (action.messageIdHeader() == null || action.messageIdHeader().isBlank()) {
      return false;
    }
    targetFolder.open(Folder.READ_ONLY);
    try {
      Message[] matches = targetFolder.search(new MessageIDTerm(action.messageIdHeader()));
      if (matches.length != 1) {
        return false;
      }
      long targetUid = targetFolder.getUID(matches[0]);
      if (targetUid <= 0) {
        return false;
      }
      emailRepository.confirmMailboxMoveApplied(
          action.id(),
          action.messageId(),
          action.targetFolderId(),
          targetFolder.getUIDValidity(),
          targetUid,
          ZonedDateTime.now(clock));
      return true;
    } finally {
      targetFolder.close(false);
    }
  }

  private IMAPFolder openSourceFolder(Store store, MailboxActionQueueRow action)
      throws MessagingException {
    Folder folder = store.getFolder(action.sourceRemoteName());
    if (!(folder instanceof IMAPFolder imapFolder)) {
      throw new MessagingException("Source folder is not an IMAP folder");
    }
    imapFolder.open(Folder.READ_WRITE);
    if (action.sourceUidValidity() != null
        && imapFolder.getUIDValidity() != action.sourceUidValidity()) {
      throw new MailboxActionConflictException("Source folder UIDVALIDITY changed");
    }
    return imapFolder;
  }

  private Store createConnectedStore(Account account) throws MessagingException {
    Store store = createStore(account);
    store.connect(
        account.imapHost(), account.imapPort(), account.imapUsername(), account.imapPassword());
    return store;
  }

  Store createStore(Account account) {
    try {
      return Session.getInstance(JakartaMailSessionProperties.imap(account), null).getStore("imap");
    } catch (NoSuchProviderException e) {
      throw new IllegalStateException("There is no imap provider, this should not happen.");
    }
  }

  private long backoffSeconds(int attemptCount) {
    return retryDelaySeconds * (1L << Math.max(0, attemptCount));
  }

  private static String abbreviateError(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    String collapsed = message.replaceAll("\\s+", " ").trim();
    return collapsed.length() <= 240 ? collapsed : collapsed.substring(0, 237) + "...";
  }

  private static class MailboxActionConflictException extends MessagingException {
    MailboxActionConflictException(String message) {
      super(message);
    }
  }

  private static class MailboxActionPermanentException extends MessagingException {
    MailboxActionPermanentException(String message) {
      super(message);
    }
  }
}
