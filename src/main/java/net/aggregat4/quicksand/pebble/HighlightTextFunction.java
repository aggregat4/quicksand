package net.aggregat4.quicksand.pebble;

import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.util.List;
import java.util.Map;
import net.aggregat4.quicksand.search.SearchHighlighter;
import org.unbescape.html.HtmlEscape;

public class HighlightTextFunction implements Function {
  @Override
  public List<String> getArgumentNames() {
    return List.of("text", "query");
  }

  @Override
  public Object execute(
      Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) {
    String text = args.get("text") == null ? "" : String.valueOf(args.get("text"));
    String query = args.get("query") == null ? "" : String.valueOf(args.get("query"));
    var pattern = SearchHighlighter.highlightPattern(query);
    if (pattern.isEmpty() || text.isBlank()) {
      return HtmlEscape.escapeHtml5(text);
    }
    StringBuilder sb = new StringBuilder();
    SearchHighlighter.highlight(
        text,
        pattern.get(),
        new SearchHighlighter.HighlightSink() {
          @Override
          public void text(String run) {
            sb.append(HtmlEscape.escapeHtml5(run));
          }

          @Override
          public void highlight(String match) {
            sb.append("<mark>");
            sb.append(HtmlEscape.escapeHtml5(match));
            sb.append("</mark>");
          }
        });
    return sb.toString();
  }
}
