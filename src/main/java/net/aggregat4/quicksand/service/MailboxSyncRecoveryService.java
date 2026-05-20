package net.aggregat4.quicksand.service;

import java.time.Clock;
import net.aggregat4.quicksand.domain.MailboxActionResolutionType;
import net.aggregat4.quicksand.repository.AccountFolderMappingRepository;
import net.aggregat4.quicksand.repository.EmailRepository;

public class MailboxSyncRecoveryService {

  private final EmailRepository emailRepository;
  private final AccountFolderMappingRepository accountFolderMappingRepository;
  private final Runnable backgroundSyncTrigger;
  private final Clock clock;

  public MailboxSyncRecoveryService(
      EmailRepository emailRepository,
      AccountFolderMappingRepository accountFolderMappingRepository,
      Runnable backgroundSyncTrigger,
      Clock clock) {
    this.emailRepository = emailRepository;
    this.accountFolderMappingRepository = accountFolderMappingRepository;
    this.backgroundSyncTrigger = backgroundSyncTrigger;
    this.clock = clock;
  }

  public boolean retryNow(int accountId, int actionId) {
    boolean updated =
        emailRepository.requestMailboxActionRetry(
            actionId, accountId, clock.instant().atZone(clock.getZone()));
    if (updated) {
      backgroundSyncTrigger.run();
    }
    return updated;
  }

  public boolean dismiss(int accountId, int actionId) {
    return emailRepository.dismissMailboxAction(
        actionId, accountId, clock.instant().atZone(clock.getZone()));
  }

  public boolean abandon(int accountId, int actionId) {
    return emailRepository.abandonMailboxAction(
        actionId, accountId, clock.instant().atZone(clock.getZone()));
  }

  public boolean rollback(int accountId, int actionId) {
    return emailRepository.rollbackMailboxAction(
        actionId, accountId, clock.instant().atZone(clock.getZone()));
  }

  public void resetLocalMirror(int accountId) {
    var now = clock.instant().atZone(clock.getZone());
    emailRepository.resolveUnresolvedMailboxActions(
        accountId, MailboxActionResolutionType.ABANDONED_BY_RESET, now);
    emailRepository.clearMirroredMailboxState(accountId);
    accountFolderMappingRepository.markMappedFoldersMissing(accountId);
    backgroundSyncTrigger.run();
  }
}
