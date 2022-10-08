package net.aggregat4.quicksand.configuration;

import com.mitchellbosecke.pebble.PebbleEngine;
import net.aggregat4.quicksand.pebble.PebbleExtension;

public class PebbleConfig {
    private static final PebbleEngine engine =
            new PebbleEngine.Builder()
                    .extension(new PebbleExtension())
                    .cacheActive(!Boolean.TRUE.equals(Boolean.parseBoolean(System.getProperty("devmode"))))
                    .build();

    public static PebbleEngine getEngine() {
        return PebbleConfig.engine;
    }
}
