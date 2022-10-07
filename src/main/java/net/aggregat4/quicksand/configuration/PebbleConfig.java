package net.aggregat4.quicksand.configuration;

import com.mitchellbosecke.pebble.PebbleEngine;
import net.aggregat4.quicksand.pebble.PebbleExtension;

public class PebbleConfig {
    private static final PebbleEngine engine = new PebbleEngine.Builder().extension(new PebbleExtension()).cacheActive(false).build();

    public static PebbleEngine getEngine() {
        return PebbleConfig.engine;
    }
}
