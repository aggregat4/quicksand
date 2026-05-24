package net.aggregat4.quicksand.pebble;

import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class SvgIconFunction implements Function {

  private static final String RESOURCE_PREFIX = "static/images/";

  @Override
  public List<String> getArgumentNames() {
    return List.of("name");
  }

  @Override
  public Object execute(
      Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) {
    String svgIconName = (String) args.get("name");
    String resourcePath = RESOURCE_PREFIX + svgIconName + ".svg";
    try (InputStream iconStream = openIconStream(resourcePath)) {
      if (iconStream == null) {
        throw new IllegalArgumentException(
            "Can not find svg icon with path '%s'".formatted(resourcePath));
      }
      return new String(iconStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Could not read svg icon with path '%s'".formatted(resourcePath), e);
    }
  }

  private static InputStream openIconStream(String resourcePath) {
    ClassLoader[] classLoaders = {
      SvgIconFunction.class.getClassLoader(),
      Thread.currentThread().getContextClassLoader(),
      ClassLoader.getSystemClassLoader()
    };
    for (ClassLoader classLoader : classLoaders) {
      if (classLoader == null) {
        continue;
      }
      InputStream iconStream = classLoader.getResourceAsStream(resourcePath);
      if (iconStream != null) {
        return iconStream;
      }
    }
    return null;
  }
}
