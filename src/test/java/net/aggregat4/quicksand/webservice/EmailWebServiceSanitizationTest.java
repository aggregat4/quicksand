package net.aggregat4.quicksand.webservice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.aggregat4.quicksand.search.HtmlSearchHighlighter;
import org.junit.jupiter.api.Test;

class EmailWebServiceSanitizationTest {

  @Test
  void noImagesPolicyStripsImageSourcesAndLinks() {
    String html =
        "<img src=\"https://example.com/img.png\" alt=\"test\"><a href=\"https://example.com\">link</a>";
    String result =
        HtmlSearchHighlighter.sanitizeAndHighlight(html, EmailWebService.NO_IMAGES_POLICY, "");

    assertFalse(result.contains("src="));
    assertFalse(result.contains("href="));
    assertFalse(result.contains("<img"));
    assertTrue(result.contains("link"));
  }

  @Test
  void imagesPolicyPreservesImageSourcesAndLinks() {
    String html =
        "<img src=\"https://example.com/img.png\" alt=\"test\"><a href=\"https://example.com\">link</a>";
    String result =
        HtmlSearchHighlighter.sanitizeAndHighlight(html, EmailWebService.IMAGES_POLICY, "");

    assertTrue(result.contains("src=\"https://example.com/img.png\""));
    assertTrue(result.contains("href=\"https://example.com\""));
  }

  @Test
  void imagesPolicyStripsScriptsAndEvents() {
    String html =
        "<p onclick=\"evil()\">text</p><script>alert(1)</script><img src=\"x\" onerror=\"bad()\">";
    String result =
        HtmlSearchHighlighter.sanitizeAndHighlight(html, EmailWebService.IMAGES_POLICY, "");

    assertFalse(result.contains("onclick"));
    assertFalse(result.contains("<script>"));
    assertFalse(result.contains("onerror"));
  }

  @Test
  void imagesPolicyStripsExternalStylesheetsAndMetaRefresh() {
    String html =
        "<link rel=\"stylesheet\" href=\"https://evil.com/style.css\"><meta http-equiv=\"refresh\" content=\"0;url=https://evil.com\">";
    String result =
        HtmlSearchHighlighter.sanitizeAndHighlight(html, EmailWebService.IMAGES_POLICY, "");

    assertFalse(result.contains("<link"));
    assertFalse(result.contains("http-equiv=\"refresh\""));
  }

  @Test
  void imagesPolicyPreservesTablesAndInlineStyles() throws IOException {
    String html = loadResource("html-emails/newsletter.html");
    String result =
        HtmlSearchHighlighter.sanitizeAndHighlight(html, EmailWebService.IMAGES_POLICY, "");

    assertTrue(result.contains("<table"));
    assertTrue(result.contains("<tr>"));
    assertTrue(result.contains("<td"));
  }

  @Test
  void noImagesPolicyPreservesStructureButStripsUrls() throws IOException {
    String html = loadResource("html-emails/styled.html");
    String result =
        HtmlSearchHighlighter.sanitizeAndHighlight(html, EmailWebService.NO_IMAGES_POLICY, "");

    assertTrue(result.contains("<div"));
    assertTrue(result.contains("<p"));
    assertTrue(result.contains("style="));
    assertFalse(result.contains("src="));
    assertFalse(result.contains("href="));
  }

  @Test
  void imagesPolicySanitizesMaliciousNewsletter() throws IOException {
    String html = loadResource("html-emails/malicious.html");
    String result =
        HtmlSearchHighlighter.sanitizeAndHighlight(html, EmailWebService.IMAGES_POLICY, "");

    assertFalse(result.contains("<script"));
    assertFalse(result.contains("onerror="));
    assertFalse(result.contains("javascript:"));
    assertFalse(result.contains("<iframe"));
    assertFalse(result.contains("<form"));
    assertFalse(result.contains("onclick="));
    assertFalse(result.contains("expression("));
  }

  private static String loadResource(String path) throws IOException {
    try (InputStream is =
        EmailWebServiceSanitizationTest.class.getClassLoader().getResourceAsStream(path)) {
      if (is == null) {
        throw new IllegalArgumentException("Resource not found: " + path);
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
