package net.aggregat4.quicksand.pebble;

import com.mitchellbosecke.pebble.template.PebbleTemplate;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

public class PebbleRenderer {

    public static String renderTemplate(Map<String, Object> context, PebbleTemplate template) {
        StringWriter writer = new StringWriter();
        try {
            template.evaluate(writer, context);
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse template: " + e.getMessage(), e);
        }
        return writer.toString();
    }
}
