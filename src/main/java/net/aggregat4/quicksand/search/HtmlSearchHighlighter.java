package net.aggregat4.quicksand.search;

import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamEventReceiver;
import org.owasp.html.HtmlStreamRenderer;
import org.owasp.html.PolicyFactory;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HtmlSearchHighlighter {
    private HtmlSearchHighlighter() {
    }

    public static String sanitizeAndHighlight(String html, PolicyFactory policy, String query) {
        StringWriter sw = new StringWriter();
        BufferedWriter bw = new BufferedWriter(sw);
        HtmlStreamRenderer renderer = HtmlStreamRenderer.create(
                bw,
                ex -> {
                    throw new AssertionError(null, ex);
                },
                x -> {
                    throw new AssertionError(x);
                });
        HtmlStreamEventReceiver receiver = SearchQueryUtils.toHighlightPattern(query)
                .<HtmlStreamEventReceiver>map(pattern -> new HighlightingHtmlReceiver(renderer, pattern))
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
            Matcher matcher = pattern.matcher(text);
            int lastEnd = 0;
            boolean foundMatch = false;
            while (matcher.find()) {
                foundMatch = true;
                if (matcher.start() > lastEnd) {
                    delegate.text(text.substring(lastEnd, matcher.start()));
                }
                delegate.openTag("mark", EMPTY_ATTRIBUTES);
                delegate.text(matcher.group());
                delegate.closeTag("mark");
                lastEnd = matcher.end();
            }
            if (!foundMatch) {
                delegate.text(text);
                return;
            }
            if (lastEnd < text.length()) {
                delegate.text(text.substring(lastEnd));
            }
        }
    }
}
