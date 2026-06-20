package net.aggregat4.quicksand.webservice;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ImportMapRenderer {
  private static final String JS_PREFIX = "/js/";
  private static final String SPECIFIER_PREFIX = "quicksand/";

  private ImportMapRenderer() {}

  public static String json() {
    StaticAssetRegistry registry = StaticAssetRegistry.get();
    Map<String, String> imports = new LinkedHashMap<>();
    for (String publicPath : registry.publicPathsWithPrefix(JS_PREFIX)) {
      if (!publicPath.endsWith(".js")) {
        continue;
      }
      String relativePath = publicPath.substring(JS_PREFIX.length());
      imports.put(SPECIFIER_PREFIX + relativePath, registry.url(publicPath));
    }
    return toJson(imports);
  }

  private static String toJson(Map<String, String> imports) {
    StringBuilder json = new StringBuilder("{\"imports\":{");
    boolean first = true;
    for (Map.Entry<String, String> entry : imports.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append('"')
          .append(escapeJson(entry.getKey()))
          .append("\":\"")
          .append(escapeJson(entry.getValue()))
          .append('"');
    }
    json.append("}}");
    return json.toString();
  }

  private static String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
