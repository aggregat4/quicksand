package net.aggregat4.quicksand.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

class HtmlSearchHighlighterTest {
  private static final PolicyFactory POLICY =
      new HtmlPolicyBuilder()
          .allowCommonBlockElements()
          .allowCommonInlineFormattingElements()
          .allowElements("a")
          .allowStandardUrlProtocols()
          .allowAttributes("href")
          .onElements("a")
          .toFactory();

  @Test
  void highlightsTextNodesWithoutTouchingAttributes() {
    String html = "<p>Hello needle <a href=\"https://needle.example/path\">link</a></p>";

    String highlighted = HtmlSearchHighlighter.sanitizeAndHighlight(html, POLICY, "needle");

    assertTrue(highlighted.contains("<mark>needle</mark>"));
    assertTrue(highlighted.contains("<a href=\"https://needle.example/path\">link</a>"));
    assertFalse(highlighted.contains("href=\"https://<mark>needle</mark>.example/path\""));
  }

  @Test
  void highlightsAfterSanitizingHtml() {
    String html = "<p>Needle<script>alert('x')</script> text</p>";

    String highlighted = HtmlSearchHighlighter.sanitizeAndHighlight(html, POLICY, "needle");

    assertTrue(highlighted.contains("<mark>Needle</mark>"));
    assertFalse(highlighted.contains("<script>"));
    assertFalse(highlighted.contains("alert('x')"));
  }
}
