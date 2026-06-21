package net.aggregat4.quicksand.domain;

import java.util.List;
import java.util.Optional;

public record EmailPage(
    List<Email> emails,
    boolean hasLeft,
    boolean hasRight,
    PageParams params,
    Optional<Double> firstRank,
    Optional<Double> lastRank) {

  public EmailPage(List<Email> emails, boolean hasLeft, boolean hasRight, PageParams params) {
    this(emails, hasLeft, hasRight, params, Optional.empty(), Optional.empty());
  }

  public EmailPage(
      List<Email> emails,
      boolean hasLeft,
      boolean hasRight,
      PageParams params,
      Optional<Double> firstRank,
      Optional<Double> lastRank) {
    this.emails = List.copyOf(emails);
    this.hasLeft = hasLeft;
    this.hasRight = hasRight;
    this.params = params;
    this.firstRank = firstRank == null ? Optional.empty() : firstRank;
    this.lastRank = lastRank == null ? Optional.empty() : lastRank;
  }
}
