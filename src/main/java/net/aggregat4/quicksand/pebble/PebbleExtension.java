package net.aggregat4.quicksand.pebble;

import com.mitchellbosecke.pebble.extension.AbstractExtension;
import com.mitchellbosecke.pebble.extension.Function;

import java.util.Map;

public class PebbleExtension extends AbstractExtension {

    @Override
    public Map<String, Function> getFunctions() {
        return Map.of("svgIcon", new SvgIconFunction());
    }
}
