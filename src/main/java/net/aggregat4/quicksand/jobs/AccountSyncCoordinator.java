package net.aggregat4.quicksand.jobs;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes all IMAP work for one account inside this JVM. */
public final class AccountSyncCoordinator {
  private final ConcurrentHashMap<Integer, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

  public void run(int accountId, Runnable operation) {
    ReentrantLock lock = accountLocks.computeIfAbsent(accountId, ignored -> new ReentrantLock());
    lock.lock();
    try {
      operation.run();
    } finally {
      lock.unlock();
    }
  }
}
