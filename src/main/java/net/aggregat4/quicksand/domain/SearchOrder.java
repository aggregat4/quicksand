package net.aggregat4.quicksand.domain;

/**
 * Search-only result ordering. Ordinary folder pages keep using {@link SortOrder} because they have
 * no FTS rank; relevance applies only to search results.
 */
public enum SearchOrder {
  NEWEST,
  OLDEST,
  BEST_MATCH;

  /** The date sort direction this search order implies for date-cursor pagination. */
  public SortOrder toSortOrder() {
    return this == OLDEST ? SortOrder.ASCENDING : SortOrder.DESCENDING;
  }

  public String getParamString() {
    return name();
  }
}
