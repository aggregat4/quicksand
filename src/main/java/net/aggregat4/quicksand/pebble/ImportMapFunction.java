package net.aggregat4.quicksand.pebble;

import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.aggregat4.quicksand.webservice.ImportMapRenderer;

public class ImportMapFunction implements Function {

  @Override
  public List<String> getArgumentNames() {
    return Collections.emptyList();
  }

  @Override
  public Object execute(
      Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) {
    return ImportMapRenderer.json();
  }
}
