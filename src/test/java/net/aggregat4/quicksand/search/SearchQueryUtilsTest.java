package net.aggregat4.quicksand.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import net.aggregat4.quicksand.search.SearchHighlighter.Segment;
import org.junit.jupiter.api.Test;

class SearchQueryUtilsTest {

  private static List<SearchTerm> terms(String query) {
    return ParsedSearchQuery.parse(query).terms();
  }

  private static String fts(String query) {
    return SearchQueryUtils.toFtsMatchQuery(query);
  }

  private static List<Segment> highlight(String query, String text) {
    Optional<Pattern> pattern = SearchHighlighter.highlightPattern(query);
    SearchHighlighter.SegmentCollector collector = new SearchHighlighter.SegmentCollector();
    SearchHighlighter.highlight(text, pattern.orElse(null), collector);
    return collector.segments();
  }

  private static String highlightedHtml(String query, String text) {
    StringBuilder sb = new StringBuilder();
    for (Segment s : highlight(query, text)) {
      sb.append(s.highlighted() ? "<mark>" + s.text() + "</mark>" : s.text());
    }
    return sb.toString();
  }

  // --- Parsing into terms -----------------------------------------------------

  @Test
  void finalUnquotedTermOfThreeOrMoreCharsIsPrefix() {
    List<SearchTerm> parsed = terms("launch dig");
    assertEquals(2, parsed.size());
    assertEquals(SearchTermKind.EXACT, parsed.get(0).kind());
    assertEquals(List.of("launch"), parsed.get(0).tokens());
    assertEquals(SearchTermKind.PREFIX, parsed.get(1).kind());
    assertEquals(List.of("dig"), parsed.get(1).tokens());
  }

  @Test
  void finalUnquotedTermShorterThanThreeCharsStaysExact() {
    List<SearchTerm> parsed = terms("launch di");
    assertEquals(2, parsed.size());
    assertEquals(SearchTermKind.EXACT, parsed.get(0).kind());
    assertEquals(SearchTermKind.EXACT, parsed.get(1).kind());
    assertEquals(List.of("di"), parsed.get(1).tokens());
  }

  @Test
  void precedingUnquotedTermsRemainExact() {
    List<SearchTerm> parsed = terms("alpha beta gamma");
    assertEquals(3, parsed.size());
    assertEquals(SearchTermKind.EXACT, parsed.get(0).kind());
    assertEquals(SearchTermKind.EXACT, parsed.get(1).kind());
    assertEquals(SearchTermKind.PREFIX, parsed.get(2).kind());
  }

  @Test
  void quotedSingleTokenIsExact() {
    List<SearchTerm> parsed = terms("launch \"digest\"");
    assertEquals(2, parsed.size());
    assertEquals(SearchTermKind.EXACT, parsed.get(1).kind());
    assertEquals(List.of("digest"), parsed.get(1).tokens());
  }

  @Test
  void quotedMultiTokenPhraseIsExactPhrase() {
    List<SearchTerm> parsed = terms("\"launch digest\"");
    assertEquals(1, parsed.size());
    assertTrue(parsed.get(0).isPhrase());
    assertEquals(List.of("launch", "digest"), parsed.get(0).tokens());
  }

  @Test
  void mixedQuotedAndPrefixTerms() {
    List<SearchTerm> parsed = terms("\"project alpha\" upd");
    assertEquals(2, parsed.size());
    assertTrue(parsed.get(0).isPhrase());
    assertEquals(SearchTermKind.PREFIX, parsed.get(1).kind());
    assertEquals(List.of("upd"), parsed.get(1).tokens());
  }

  @Test
  void quotedFinalTermIsNotPrefix() {
    List<SearchTerm> parsed = terms("launch \"dig\"");
    assertEquals(2, parsed.size());
    assertEquals(SearchTermKind.EXACT, parsed.get(1).kind());
    assertEquals(List.of("dig"), parsed.get(1).tokens());
  }

  @Test
  void unmatchedTrailingQuoteIsOrdinaryPunctuation() {
    List<SearchTerm> parsed = terms("launch dig\"");
    assertEquals(2, parsed.size());
    assertEquals(SearchTermKind.PREFIX, parsed.get(1).kind());
  }

  @Test
  void unmatchedLeadingQuoteBecomesUnquoted() {
    List<SearchTerm> parsed = terms("\"launch digest");
    assertEquals(2, parsed.size());
    assertEquals(SearchTermKind.EXACT, parsed.get(0).kind());
    assertEquals(SearchTermKind.PREFIX, parsed.get(1).kind());
  }

  @Test
  void punctuationSplitsUnquotedTokens() {
    List<SearchTerm> parsed = terms("foo,bar!baz");
    assertEquals(List.of("foo"), parsed.get(0).tokens());
    assertEquals(List.of("bar"), parsed.get(1).tokens());
    assertEquals(List.of("baz"), parsed.get(2).tokens());
  }

  @Test
  void unicodeLettersAreTokens() {
    List<SearchTerm> parsed = terms("über Café");
    assertEquals("uber", parsed.get(0).firstToken());
    assertEquals("cafe", parsed.get(1).firstToken());
    assertEquals(SearchTermKind.PREFIX, parsed.get(1).kind());
  }

  @Test
  void supplementaryUnicodeLettersAreTokens() {
    List<SearchTerm> parsed = terms("𐐀bc");
    assertEquals(1, parsed.size());
    assertEquals("𐐨bc", parsed.getFirst().firstToken());
    assertEquals(SearchTermKind.PREFIX, parsed.getFirst().kind());
  }

  @Test
  void caseIsNormalizedToLowerCase() {
    List<SearchTerm> parsed = terms("LAUNCH Dig");
    assertEquals(List.of("launch"), parsed.get(0).tokens());
    assertEquals(List.of("dig"), parsed.get(1).tokens());
  }

  @Test
  void diacriticsStrippedForNormalization() {
    assertEquals("cafe", ParsedSearchQuery.normalize("Café"));
    assertEquals("naive", ParsedSearchQuery.normalize("naïve"));
  }

  // --- FTS expression ---------------------------------------------------------

  @Test
  void ftsExpressionForPrefixAndExact() {
    assertEquals("\"launch\" AND dig*", fts("launch dig"));
  }

  @Test
  void ftsExpressionForQuotedPhrase() {
    assertEquals("\"launch digest\"", fts("\"launch digest\""));
  }

  @Test
  void ftsExpressionForMixedQuery() {
    assertEquals("\"project alpha\" AND upd*", fts("\"project alpha\" upd"));
  }

  @Test
  void ftsExpressionForShortFinalTermStaysExact() {
    assertEquals("\"launch\" AND \"di\"", fts("launch di"));
  }

  @Test
  void ftsExpressionEscapesEmbeddedQuotes() {
    // Punctuation inside a quoted phrase is tokenized, not treated as raw FTS syntax.
    assertEquals("\"foo bar\"", fts("\"foo,bar\""));
  }

  @Test
  void ftsExpressionForEmptyIsDefensiveQuotedEmpty() {
    assertEquals("\"\"", fts(""));
    assertEquals("\"\"", fts("   "));
  }

  @Test
  void ftsExpressionNeverLeavesUserSyntaxUnquoted() {
    // Even if the user types FTS operators, they are tokenized as ordinary quoted terms.
    assertEquals("\"a\" AND \"or\" AND \"b\"", fts("a OR b"));
    assertEquals("\"a\" AND \"and\" AND \"b\"", fts("a AND b"));
  }

  // --- Highlighting -----------------------------------------------------------

  @Test
  void prefixHighlightCoversOnlyPrefixAtTokenStart() {
    assertEquals(
        "<mark>Launch</mark> <mark>Dig</mark>est", highlightedHtml("launch dig", "Launch Digest"));
  }

  @Test
  void exactHighlightCoversWholeTokenOnly() {
    assertEquals(
        "<mark>Launch</mark> <mark>Dig</mark>", highlightedHtml("launch \"dig\"", "Launch Dig"));
  }

  @Test
  void exactTermDoesNotHighlightMidToken() {
    assertEquals("<mark>Launch</mark> Digest", highlightedHtml("launch \"dig\"", "Launch Digest"));
  }

  @Test
  void prefixDoesNotHighlightMidToken() {
    // "dig" as a prefix must match at a token boundary, not inside "bridging".
    assertEquals("bridging", highlightedHtml("dig", "bridging"));
  }

  @Test
  void phraseTokensAreHighlightedIndividually() {
    String result = highlightedHtml("\"launch digest\"", "Launch Digest ready");
    assertEquals("<mark>Launch</mark> <mark>Digest</mark> ready", result);
  }

  @Test
  void diacriticsMatchInHighlight() {
    assertEquals("<mark>Café</mark> au lait", highlightedHtml("cafe", "Café au lait"));
  }

  @Test
  void decomposedDiacriticsArePreservedInHighlight() {
    assertEquals("<mark>Café</mark> au lait", highlightedHtml("cafe", "Café au lait"));
  }

  @Test
  void supplementaryUnicodeLettersAreHighlightedWithoutCorruption() {
    assertEquals("<mark>𐐀bc</mark> def", highlightedHtml("𐐀bc", "𐐀bc def"));
  }

  @Test
  void noHighlightWhenQueryEmpty() {
    assertEquals("nothing here", highlightedHtml("", "nothing here"));
  }

  @Test
  void highlightIsCaseInsensitive() {
    assertEquals("<mark>LAUNCH</mark>", highlightedHtml("launch", "LAUNCH"));
  }

  @Test
  void prefixAndExactCoexist() {
    // "dig" is a prefix; "launch" is exact. Both highlight in their respective token roles.
    assertEquals(
        "<mark>Launch</mark> <mark>Dig</mark>est", highlightedHtml("launch dig", "Launch Digest"));
  }

  @Test
  void tokenizeReturnsAllNormalizedTokens() {
    assertEquals(List.of("launch", "dig"), SearchQueryUtils.tokenize("Launch Dig"));
  }

  @Test
  void highlightPatternEmptyWhenNothingParses() {
    assertFalse(SearchQueryUtils.toHighlightPattern("!!!").isPresent());
  }
}
