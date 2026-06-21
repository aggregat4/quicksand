package net.aggregat4.quicksand.search;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.regex.Pattern;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamEventReceiver;
import org.owasp.html.HtmlStreamRenderer;
import org.owasp.html.PolicyFactory;

public final class HtmlSearchHighlighter {
  private HtmlSearchHighlighter() {}

  public static String sanitizeAndHighlight(String html, PolicyFactory policy, String query) {
    StringWriter sw = new StringWriter();
    BufferedWriter bw = new BufferedWriter(sw);
    HtmlStreamRenderer renderer =
        HtmlStreamRenderer.create(
            bw,
            ex -> {
              throw new AssertionError(null, ex);
            },
            x -> {
              throw new AssertionError(x);
            });
    HtmlStreamEventReceiver receiver =
        SearchHighlighter.highlightPattern(query)
            .<HtmlStreamEventReceiver>map(
                pattern -> new HighlightingHtmlReceiver(renderer, pattern))
            .orElse(renderer);
    HtmlSanitizer.sanitize(html == null ? "" : html, policy.apply(receiver));
    return sw.toString();
  }

  private static final class HighlightingHtmlReceiver implements HtmlStreamEventReceiver {
    private static final List<String> EMPTY_ATTRIBUTES = List.of();

    private final HtmlStreamEventReceiver delegate;
    private final Pattern pattern;

    private HighlightingHtmlReceiver(HtmlStreamEventReceiver delegate, Pattern pattern) {
      this.delegate = delegate;
      this.pattern = pattern;
    }

    @Override
    public void openDocument() {
      delegate.openDocument();
    }

    @Override
    public void closeDocument() {
      delegate.closeDocument();
    }

    @Override
    public void openTag(String elementName, List<String> attrs) {
      delegate.openTag(elementName, attrs);
    }

    @Override
    public void closeTag(String elementName) {
      delegate.closeTag(elementName);
    }

    @Override
    public void text(String text) {
      if (text == null || text.isEmpty()) {
        delegate.text(text == null ? "" : text);
        return;
      }
      SearchHighlighter.highlight(
          text,
          pattern,
          new SearchHighlighter.HighlightSink() {
            @Override
            public void text(String run) {
              delegate.text(run);
            }

            @Override
            public void highlight(String match) {
              delegate.openTag("mark", EMPTY_ATTRIBUTES);
              delegate.text(match);
              delegate.closeTag("mark");
            }
          });
    }
  }
}
