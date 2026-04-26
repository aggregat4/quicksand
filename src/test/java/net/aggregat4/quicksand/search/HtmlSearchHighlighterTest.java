package net.aggregat4.quicksand.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

  private static final PolicyFactory IMAGES_LIKE_POLICY =
      new HtmlPolicyBuilder()
          .allowCommonBlockElements()
          .allowCommonInlineFormattingElements()
          .allowElements("table", "tr", "td", "a", "img")
          .allowStyling()
          .allowStandardUrlProtocols()
          .allowAttributes("src", "href")
          .globally()
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

  @Test
  void sanitizesComplexNewsletter() throws IOException {
    String html = loadResource("html-emails/newsletter.html");
    String result = HtmlSearchHighlighter.sanitizeAndHighlight(html, IMAGES_LIKE_POLICY, "");

    assertFalse(result.contains("<script>"));
    assertFalse(result.contains("alert(\"Script in an email!\")"));
    assertTrue(result.contains("<table"));
    assertTrue(result.contains("<tr>"));
    assertTrue(result.contains("<a"));
    assertTrue(result.contains("<img"));
  }

  @Test
  void stripsMaliciousContent() throws IOException {
    String html = loadResource("html-emails/malicious.html");
    String result = HtmlSearchHighlighter.sanitizeAndHighlight(html, IMAGES_LIKE_POLICY, "");

    assertFalse(result.contains("<script"));
    assertFalse(result.contains("onerror="));
    assertFalse(result.contains("javascript:"));
    assertFalse(result.contains("<iframe"));
    assertFalse(result.contains("<form"));
    assertFalse(result.contains("<object"));
    assertFalse(result.contains("<embed"));
    assertFalse(result.contains("onclick="));
    assertFalse(result.contains("<link"));
    assertFalse(result.contains("http-equiv=\"refresh\""));
    assertFalse(result.contains("<style"));
    assertFalse(result.contains("expression("));
  }

  @Test
  void preservesBenignInlineStyles() throws IOException {
    String html = loadResource("html-emails/styled.html");
    String result = HtmlSearchHighlighter.sanitizeAndHighlight(html, IMAGES_LIKE_POLICY, "");

    assertTrue(result.contains("background-color:#f0f0f0"));
    assertTrue(result.contains("font-size:24px"));
    assertTrue(result.contains("color:#000000"));
  }

  @Test
  void highlightsInComplexNestedHtml() throws IOException {
    String html = loadResource("html-emails/newsletter.html");
    String result =
        HtmlSearchHighlighter.sanitizeAndHighlight(html, IMAGES_LIKE_POLICY, "Postdrop");

    assertTrue(result.contains("<mark>Postdrop</mark>"));
    assertTrue(result.contains("<table"));
  }

  @Test
  void handlesNullHtml() {
    String result = HtmlSearchHighlighter.sanitizeAndHighlight(null, POLICY, "needle");
    assertEquals("", result);
  }

  @Test
  void handlesEmptyHtml() {
    String result = HtmlSearchHighlighter.sanitizeAndHighlight("", POLICY, "needle");
    assertEquals("", result);
  }

  private static String loadResource(String path) throws IOException {
    try (InputStream is =
        HtmlSearchHighlighterTest.class.getClassLoader().getResourceAsStream(path)) {
      if (is == null) {
        throw new IllegalArgumentException("Resource not found: " + path);
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
