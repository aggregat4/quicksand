package net.aggregat4.quicksand.jobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AccountSyncCoordinatorTest {

  @Test
  void serializesOperationsForSameAccount() throws Exception {
    AccountSyncCoordinator coordinator = new AccountSyncCoordinator();
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondAttempted = new CountDownLatch(1);
    AtomicBoolean secondEntered = new AtomicBoolean();

    Thread first =
        Thread.ofVirtual()
            .start(
                () ->
                    coordinator.run(
                        1,
                        () -> {
                          firstEntered.countDown();
                          await(releaseFirst);
                        }));
    assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
    Thread second =
        Thread.ofVirtual()
            .start(
                () -> {
                  secondAttempted.countDown();
                  coordinator.run(1, () -> secondEntered.set(true));
                });

    assertTrue(secondAttempted.await(1, TimeUnit.SECONDS));
    assertFalse(secondEntered.get());
    releaseFirst.countDown();
    first.join();
    second.join();
    assertTrue(secondEntered.get());
  }

  @Test
  void permitsDifferentAccountsToProgressIndependently() throws Exception {
    AccountSyncCoordinator coordinator = new AccountSyncCoordinator();
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicBoolean secondEntered = new AtomicBoolean();

    Thread first =
        Thread.ofVirtual()
            .start(
                () ->
                    coordinator.run(
                        1,
                        () -> {
                          firstEntered.countDown();
                          await(releaseFirst);
                        }));
    assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
    Thread second =
        Thread.ofVirtual().start(() -> coordinator.run(2, () -> secondEntered.set(true)));

    second.join();
    assertTrue(secondEntered.get());
    releaseFirst.countDown();
    first.join();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
