package net.aggregat4.quicksand.time;

import java.time.Clock;
import java.util.Objects;

public final class ApplicationClock {
  private static volatile Clock current = Clock.systemDefaultZone();

  private ApplicationClock() {}

  public static Clock current() {
    return current;
  }

  public static void set(Clock clock) {
    current = Objects.requireNonNull(clock);
  }
}
