package net.aggregat4.quicksand.pebble;

import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import net.aggregat4.quicksand.search.SearchQueryUtils;
import org.unbescape.html.HtmlEscape;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HighlightTextFunction implements Function {
    @Override
    public List<String> getArgumentNames() {
        return List.of("text", "query");
    }

    @Override
    public Object execute(Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) {
        String text = args.get("text") == null ? "" : String.valueOf(args.get("text"));
        String query = args.get("query") == null ? "" : String.valueOf(args.get("query"));
        List<String> tokens = SearchQueryUtils.tokenize(query).stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        if (tokens.isEmpty() || text.isBlank()) {
            return HtmlEscape.escapeHtml5(text);
        }
        Pattern pattern = Pattern.compile(
                tokens.stream().map(Pattern::quote).reduce((left, right) -> left + "|" + right).orElseThrow(),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            sb.append(HtmlEscape.escapeHtml5(text.substring(lastEnd, matcher.start())));
            sb.append("<mark>");
            sb.append(HtmlEscape.escapeHtml5(matcher.group()));
            sb.append("</mark>");
            lastEnd = matcher.end();
        }
        sb.append(HtmlEscape.escapeHtml5(text.substring(lastEnd)));
        return sb.toString();
    }
}
