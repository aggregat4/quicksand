package net.aggregat4.quicksand.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The structured result of parsing a Quicksand search query.
 *
 * <p>The parser splits user input into quoted phrases and unquoted Unicode letter/number tokens.
 * Quoted text and all non-final unquoted terms are exact; only the final syntactic term may become
 * a prefix, and only when it is unquoted and its normalized token has at least three characters. An
 * unmatched trailing quote is treated as ordinary punctuation rather than rejecting the request.
 *
 * <p>The FTS5 {@code MATCH} expression is built here so that user-provided FTS syntax is never
 * passed through to SQLite. Quoted terms and phrases are emitted as double-quoted FTS strings (with
 * embedded quotes doubled); prefix terms are emitted as {@code token*}.
 */
public final class ParsedSearchQuery {
  /** Minimum normalized length for the final unquoted term to be treated as a prefix. */
  public static final int PREFIX_MIN_LENGTH = 3;

  private final List<SearchTerm> terms;

  private ParsedSearchQuery(List<SearchTerm> terms) {
    this.terms = List.copyOf(terms);
  }

  public List<SearchTerm> terms() {
    return terms;
  }

  public boolean isEmpty() {
    return terms.isEmpty();
  }

  public static ParsedSearchQuery parse(String query) {
    if (query == null || query.isBlank()) {
      return new ParsedSearchQuery(List.of());
    }
    List<RawTerm> rawTerms = lex(query);
    List<SearchTerm> terms = new ArrayList<>(rawTerms.size());
    for (int i = 0; i < rawTerms.size(); i++) {
      RawTerm raw = rawTerms.get(i);
      List<String> tokens = new ArrayList<>(raw.tokens().size());
      for (String rawToken : raw.tokens()) {
        String normalized = normalize(rawToken);
        if (!normalized.isEmpty()) {
          tokens.add(normalized);
        }
      }
      if (tokens.isEmpty()) {
        continue;
      }
      boolean isFinal = i == rawTerms.size() - 1;
      boolean prefix =
          isFinal
              && !raw.quoted()
              && tokens.size() == 1
              && tokens.getFirst().codePointCount(0, tokens.getFirst().length())
                  >= PREFIX_MIN_LENGTH;
      terms.add(new SearchTerm(prefix ? SearchTermKind.PREFIX : SearchTermKind.EXACT, tokens));
    }
    return new ParsedSearchQuery(terms);
  }

  /** Splits the raw query into quoted and unquoted raw terms without normalizing tokens yet. */
  private static List<RawTerm> lex(String query) {
    List<RawTerm> terms = new ArrayList<>();
    StringBuilder unquoted = new StringBuilder();
    StringBuilder quoted = new StringBuilder();
    boolean inQuote = false;
    for (int i = 0; i < query.length(); i++) {
      char c = query.charAt(i);
      if (c == '"') {
        if (inQuote) {
          flushQuoted(terms, quoted);
          quoted.setLength(0);
          inQuote = false;
        } else {
          flushUnquoted(terms, unquoted);
          unquoted.setLength(0);
          inQuote = true;
        }
      } else if (inQuote) {
        quoted.append(c);
      } else {
        unquoted.append(c);
      }
    }
    // An unmatched trailing quote is ordinary punctuation: flush its accumulated content as
    // unquoted text so the user still gets a normal token search instead of an error.
    flushUnquoted(terms, unquoted);
    if (inQuote) {
      flushUnquoted(terms, quoted);
    }
    return terms;
  }

  private static void flushUnquoted(List<RawTerm> terms, StringBuilder buffer) {
    for (String token : splitTokens(buffer.toString())) {
      terms.add(new RawTerm(false, List.of(token)));
    }
  }

  private static void flushQuoted(List<RawTerm> terms, StringBuilder buffer) {
    List<String> tokens = splitTokens(buffer.toString());
    if (tokens.isEmpty()) {
      return;
    }
    terms.add(new RawTerm(true, tokens));
  }

  private static List<String> splitTokens(String text) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int offset = 0; offset < text.length(); ) {
      int codePoint = text.codePointAt(offset);
      if (isTokenCodePoint(codePoint)) {
        current.appendCodePoint(codePoint);
      } else if (!current.isEmpty()) {
        tokens.add(current.toString());
        current.setLength(0);
      }
      offset += Character.charCount(codePoint);
    }
    if (!current.isEmpty()) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  private static boolean isTokenCodePoint(int codePoint) {
    return Character.isLetter(codePoint) || Character.isDigit(codePoint);
  }

  /**
   * Lowercases and strips combining marks, mirroring the FTS table's {@code unicode61
   * remove_diacritics 2} tokenizer closely enough for query and highlight construction.
   */
  public static String normalize(String token) {
    String lower = token.toLowerCase(Locale.ROOT);
    String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
    StringBuilder sb = new StringBuilder(decomposed.length());
    for (int offset = 0; offset < decomposed.length(); ) {
      int codePoint = decomposed.codePointAt(offset);
      if (!isCombiningMark(codePoint)) {
        sb.appendCodePoint(codePoint);
      }
      offset += Character.charCount(codePoint);
    }
    return sb.toString();
  }

  private static boolean isCombiningMark(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK;
  }

  private record RawTerm(boolean quoted, List<String> tokens) {}

  /**
   * Builds the FTS5 {@code MATCH} expression. Exact terms and phrases are double-quoted; prefix
   * terms append {@code *}. Terms are AND-joined.
   */
  public String toFtsMatchQuery() {
    if (terms.isEmpty()) {
      // Preserve prior behavior for empty input: a quoted empty string. The HTTP handler redirects
      // blank queries before reaching the repository, so this is a defensive fallback.
      return "\"\"";
    }
    List<String> parts = new ArrayList<>(terms.size());
    for (SearchTerm term : terms) {
      String joined = String.join(" ", term.tokens());
      String escaped = joined.replace("\"", "\"\"");
      if (term.kind() == SearchTermKind.PREFIX) {
        parts.add(escaped + "*");
      } else {
        parts.add("\"" + escaped + "\"");
      }
    }
    return String.join(" AND ", parts);
  }

  /**
   * Builds a diacritic- and case-insensitive highlight pattern that respects token boundaries.
   *
   * <p>Exact tokens match a complete token (negative lookbehind and lookahead for letter/number).
   * Prefix tokens match only the prefix at the start of a token (negative lookbehind, no lookahead,
   * so the prefix length is highlighted even when the token continues). Match longer alternatives
   * first.
   *
   * <p>The returned pattern is matched against NFD-normalized text by {@link SearchHighlighter};
   * the pattern tokens are already normalized (base letters only), so combining marks in the text
   * do not block a match.
   */
  public Pattern toHighlightPattern() {
    List<String> alternatives = new ArrayList<>();
    for (SearchTerm term : terms) {
      for (String token : term.tokens()) {
        String quoted = Pattern.quote(token);
        if (term.kind() == SearchTermKind.PREFIX) {
          alternatives.add("(?<![\\p{L}\\p{N}])" + quoted);
        } else {
          alternatives.add("(?<![\\p{L}\\p{N}])" + quoted + "(?![\\p{L}\\p{N}])");
        }
      }
    }
    if (alternatives.isEmpty()) {
      return null;
    }
    // Longest first so that an exact "digest" wins over a prefix "dig" at the same position.
    alternatives.sort((a, b) -> Integer.compare(b.length(), a.length()));
    return Pattern.compile(
        String.join("|", alternatives), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }
}
