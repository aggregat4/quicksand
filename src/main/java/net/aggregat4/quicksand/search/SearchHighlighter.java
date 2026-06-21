package net.aggregat4.quicksand.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Highlights parsed-search matches in plain text while respecting token boundaries and diacritics.
 *
 * <p>Matching runs against a normalized copy of the text where combining marks have been stripped
 * (mirroring the FTS {@code unicode61 remove_diacritics 2} tokenizer), so that base-letter query
 * tokens match accented text. An offset map back to the original string lets highlighted {@code
 * <mark>} regions wrap the original characters (including their combining marks in NFC input).
 *
 * <p>Prefix terms highlight only the prefix portion at the start of a token; exact terms highlight
 * a complete token. Because combining marks are stripped before matching, the pattern's
 * lookbehind/lookahead see real base-letter token boundaries, so a prefix never matches in the
 * middle of a token.
 */
public final class SearchHighlighter {
  private SearchHighlighter() {}

  /** Returns the highlight pattern for a query, or empty if nothing should be highlighted. */
  public static Optional<Pattern> highlightPattern(String query) {
    return Optional.ofNullable(ParsedSearchQuery.parse(query).toHighlightPattern());
  }

  /** Applies highlights to {@code text}, dispatching runs to {@code sink}. */
  public static void highlight(String text, Pattern pattern, HighlightSink sink) {
    if (text == null || text.isEmpty()) {
      sink.text("");
      return;
    }
    if (pattern == null) {
      sink.text(text);
      return;
    }
    NormalizedText normalized = normalize(text);
    Matcher matcher = pattern.matcher(normalized.text());
    int lastOriginalEnd = 0;
    boolean found = false;
    while (matcher.find()) {
      found = true;
      int originalStart = normalized.originStart(matcher.start());
      int originalEnd = normalized.originEnd(matcher.end() - 1);
      if (originalStart > lastOriginalEnd) {
        sink.text(text.substring(lastOriginalEnd, originalStart));
      }
      sink.highlight(text.substring(originalStart, originalEnd));
      lastOriginalEnd = originalEnd;
    }
    if (!found) {
      sink.text(text);
      return;
    }
    if (lastOriginalEnd < text.length()) {
      sink.text(text.substring(lastOriginalEnd));
    }
  }

  private static NormalizedText normalize(String text) {
    StringBuilder stripped = new StringBuilder(text.length());
    List<Integer> originStarts = new ArrayList<>();
    List<Integer> originEnds = new ArrayList<>();
    for (int clusterStart = 0; clusterStart < text.length(); ) {
      int clusterEnd = clusterStart + Character.charCount(text.codePointAt(clusterStart));
      while (clusterEnd < text.length() && isCombiningMark(text.codePointAt(clusterEnd))) {
        clusterEnd += Character.charCount(text.codePointAt(clusterEnd));
      }
      String cluster = text.substring(clusterStart, clusterEnd);
      String decomposed = Normalizer.normalize(cluster, Normalizer.Form.NFD);
      for (int offset = 0; offset < decomposed.length(); ) {
        int codePoint = decomposed.codePointAt(offset);
        int charCount = Character.charCount(codePoint);
        if (isCombiningMark(codePoint)) {
          offset += charCount;
          continue;
        }
        stripped.appendCodePoint(codePoint);
        for (int i = 0; i < charCount; i++) {
          originStarts.add(clusterStart);
          originEnds.add(clusterEnd);
        }
        offset += charCount;
      }
      clusterStart = clusterEnd;
    }
    return new NormalizedText(stripped.toString(), originStarts, originEnds);
  }

  private static boolean isCombiningMark(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK;
  }

  private record NormalizedText(String text, List<Integer> originStarts, List<Integer> originEnds) {
    int originStart(int normalizedPosition) {
      if (originStarts.isEmpty()) {
        return 0;
      }
      if (normalizedPosition >= originStarts.size()) {
        return originEnds.getLast();
      }
      return originStarts.get(normalizedPosition);
    }

    int originEnd(int normalizedPosition) {
      if (originEnds.isEmpty()) {
        return 0;
      }
      if (normalizedPosition >= originEnds.size()) {
        return originEnds.getLast();
      }
      return originEnds.get(normalizedPosition);
    }
  }

  /** Receives highlighted and unhighlighted text runs from {@link #highlight}. */
  public interface HighlightSink {
    void text(String text);

    void highlight(String match);
  }

  /** Collects plain and highlighted segments into a list of {@link Segment}s. */
  public static final class SegmentCollector implements HighlightSink {
    private final List<Segment> segments = new ArrayList<>();
    private final StringBuilder pending = new StringBuilder();

    @Override
    public void text(String text) {
      pending.append(text);
    }

    @Override
    public void highlight(String match) {
      flushPending();
      segments.add(new Segment(true, match));
    }

    public void flushPending() {
      if (!pending.isEmpty()) {
        segments.add(new Segment(false, pending.toString()));
        pending.setLength(0);
      }
    }

    public List<Segment> segments() {
      flushPending();
      return List.copyOf(segments);
    }
  }

  public record Segment(boolean highlighted, String text) {}
}
