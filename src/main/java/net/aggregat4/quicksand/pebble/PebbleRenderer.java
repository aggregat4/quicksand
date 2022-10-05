package net.aggregat4.quicksand.pebble;

import com.mitchellbosecke.pebble.template.PebbleTemplate;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

public class PebbleRenderer {

    public static String renderTemplate(Map<String, Object> context, PebbleTemplate template) throws IOException {
        StringWriter writer = new StringWriter();
        template.evaluate(writer, context);
        return writer.toString();
    }
}
