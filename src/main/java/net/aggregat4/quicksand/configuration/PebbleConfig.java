package net.aggregat4.quicksand.configuration;

import com.mitchellbosecke.pebble.PebbleEngine;

public class PebbleConfig {
    private static final PebbleEngine engine = new PebbleEngine.Builder().cacheActive(false).build();

    public static PebbleEngine getEngine() {
        return PebbleConfig.engine;
    }
}
