package net.aggregat4.quicksand.search;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Adapters around {@link ParsedSearchQuery} for callers that work with the raw query string.
 *
 * <p>Tokenization, FTS5 expression building, and highlight-pattern construction all flow through
 * the parsed query so that prefix, exact, and phrase semantics stay consistent.
 */
public final class SearchQueryUtils {
  private SearchQueryUtils() {}

  public static List<String> tokenize(String query) {
    return ParsedSearchQuery.parse(query).terms().stream()
        .flatMap(term -> term.tokens().stream())
        .toList();
  }

  public static Optional<Pattern> toHighlightPattern(String query) {
    return SearchHighlighter.highlightPattern(query);
  }

  public static String toFtsMatchQuery(String query) {
    return ParsedSearchQuery.parse(query).toFtsMatchQuery();
  }
}
