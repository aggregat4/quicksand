package net.aggregat4.quicksand.pebble;

import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Function;

import java.util.Map;

public class PebbleExtension extends AbstractExtension {

    @Override
    public Map<String, Function> getFunctions() {
        return Map.of("svgIcon", new SvgIconFunction());
    }
}
