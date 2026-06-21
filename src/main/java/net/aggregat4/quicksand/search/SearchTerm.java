package net.aggregat4.quicksand.search;

import java.util.List;

/**
 * A single parsed search term.
 *
 * <p>Quoted terms and all non-final unquoted terms are {@link SearchTermKind#EXACT}. The final
 * syntactic term is {@link SearchTermKind#PREFIX} when it is unquoted and its normalized token has
 * at least three characters; otherwise it is exact. A multi-token exact term is an adjacent phrase.
 */
public record SearchTerm(SearchTermKind kind, List<String> tokens) {
  public SearchTerm {
    tokens = List.copyOf(tokens);
  }

  public boolean isPhrase() {
    return tokens.size() > 1;
  }

  public String firstToken() {
    return tokens.isEmpty() ? "" : tokens.getFirst();
  }
}
