package net.aggregat4.quicksand.pebble;

import com.mitchellbosecke.pebble.extension.Function;
import com.mitchellbosecke.pebble.template.EvaluationContext;
import com.mitchellbosecke.pebble.template.PebbleTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class SvgIconFunction implements Function {

    @Override
    public List<String> getArgumentNames() {
        return List.of("name");
    }

    @Override
    public Object execute(Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) {
        String svgIconName = (String) args.get("name");
        String fullPath = "/static/images/" + svgIconName + ".svg";
        try (var iconStream = SvgIconFunction.class.getResourceAsStream(fullPath)) {
            return new String(iconStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Can not find svg icon with path '%s'".formatted(fullPath));
        }
    }

}
