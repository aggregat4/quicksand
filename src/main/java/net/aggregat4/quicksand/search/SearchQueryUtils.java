package net.aggregat4.quicksand.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchQueryUtils {
    private static final Pattern SEARCH_TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");

    private SearchQueryUtils() {
    }

    public static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Matcher matcher = SEARCH_TOKEN_PATTERN.matcher(query.toLowerCase(Locale.ROOT));
        Set<String> tokens = new LinkedHashSet<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return new ArrayList<>(tokens);
    }

    public static String toFtsMatchQuery(String query) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return "\"%s\"".formatted(query == null ? "" : query.trim().replace("\"", "\"\""));
        }
        return String.join(" AND ", tokens);
    }
}
