package net.aggregat4.quicksand.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MailboxUpdateBroadcasterTest {

  @Test
  void publishNotifiesSubscribersForAccount() throws Exception {
    MailboxUpdateBroadcaster broadcaster = new MailboxUpdateBroadcaster();
    ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);

    try (AutoCloseable ignored = broadcaster.subscribe(7, queue)) {
      broadcaster.publishMailboxUpdated(7);
      assertSame(MailboxUpdateBroadcaster.wakeupToken(), queue.poll(1, TimeUnit.SECONDS));
    }

    broadcaster.publishMailboxUpdated(7);
    assertTrue(queue.isEmpty());
  }

  @Test
  void publishDoesNotNotifyOtherAccounts() throws Exception {
    MailboxUpdateBroadcaster broadcaster = new MailboxUpdateBroadcaster();
    ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);

    try (AutoCloseable ignored = broadcaster.subscribe(1, queue)) {
      broadcaster.publishMailboxUpdated(2);
      assertTrue(queue.isEmpty());
    }
  }
}
