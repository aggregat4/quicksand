package net.aggregat4.quicksand.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    public static Optional<Pattern> toHighlightPattern(String query) {
        List<String> tokens = tokenize(query).stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Pattern.compile(
                tokens.stream().map(Pattern::quote).reduce((left, right) -> left + "|" + right).orElseThrow(),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    public static String toFtsMatchQuery(String query) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return "\"%s\"".formatted(query == null ? "" : query.trim().replace("\"", "\"\""));
        }
        return String.join(" AND ", tokens);
    }
}
