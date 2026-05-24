package net.aggregat4.quicksand.pebble;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SvgIconFunctionTest {

  private static final PebbleTemplate NO_TEMPLATE = null;
  private static final EvaluationContext NO_CONTEXT = null;

  @Test
  void loadsKnownIconFromClasspath() {
    SvgIconFunction function = new SvgIconFunction();

    Object svg = function.execute(Map.of("name", "folders"), NO_TEMPLATE, NO_CONTEXT, 1);

    assertTrue(svg instanceof String);
    assertTrue(((String) svg).contains("<svg"));
  }

  @Test
  void missingIconReportsClasspathPath() {
    SvgIconFunction function = new SvgIconFunction();

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> function.execute(Map.of("name", "missing-icon"), NO_TEMPLATE, NO_CONTEXT, 1));

    assertTrue(error.getMessage().contains("static/images/missing-icon.svg"));
    assertFalse(error.getMessage().contains("/static/images/"));
  }
}
