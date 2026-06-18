package net.aggregat4.quicksand.pebble;

import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.util.List;
import java.util.Map;
import net.aggregat4.quicksand.webservice.StaticAssetRegistry;

public class AssetUrlFunction implements Function {

  @Override
  public List<String> getArgumentNames() {
    return List.of("path");
  }

  @Override
  public Object execute(
      Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) {
    String path = (String) args.get("path");
    return StaticAssetRegistry.get().url(path);
  }
}
