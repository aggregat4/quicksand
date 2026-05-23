package net.aggregat4.quicksand.time;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Objects;

public final class ApplicationClock {
  private static final ZoneId DEFAULT_ZONE = ZoneId.of(System.getProperty("user.timezone", "UTC"));

  private static volatile Clock current = Clock.system(DEFAULT_ZONE);

  private ApplicationClock() {}

  public static Clock current() {
    return current;
  }

  public static ZoneId zone() {
    return current.getZone();
  }

  public static void set(Clock clock) {
    current = Objects.requireNonNull(clock);
  }
}
