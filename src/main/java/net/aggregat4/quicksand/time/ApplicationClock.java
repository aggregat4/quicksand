package net.aggregat4.quicksand.time;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Objects;

public final class ApplicationClock {
  public static final ZoneId ZONE = ZoneId.of(System.getProperty("user.timezone", "UTC"));

  private static volatile Clock current = Clock.system(ZONE);

  private ApplicationClock() {}

  public static Clock current() {
    return current;
  }

  public static void set(Clock clock) {
    current = Objects.requireNonNull(clock);
  }
}
