package net.aggregat4.quicksand.service;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

public final class MailboxUpdateBroadcaster {
  private static final Object WAKEUP = new Object();

  private final ConcurrentHashMap<Integer, Set<BlockingQueue<Object>>> subscribers =
      new ConcurrentHashMap<>();

  public AutoCloseable subscribe(int accountId, BlockingQueue<Object> queue) {
    subscribers.computeIfAbsent(accountId, ignored -> ConcurrentHashMap.newKeySet()).add(queue);
    return () -> {
      Set<BlockingQueue<Object>> accountSubscribers = subscribers.get(accountId);
      if (accountSubscribers != null) {
        accountSubscribers.remove(queue);
        if (accountSubscribers.isEmpty()) {
          subscribers.remove(accountId, accountSubscribers);
        }
      }
    };
  }

  public void publishMailboxUpdated(int accountId) {
    Set<BlockingQueue<Object>> accountSubscribers = subscribers.get(accountId);
    if (accountSubscribers == null || accountSubscribers.isEmpty()) {
      return;
    }
    for (BlockingQueue<Object> queue : accountSubscribers) {
      queue.offer(WAKEUP);
    }
  }

  public static Object wakeupToken() {
    return WAKEUP;
  }
}
