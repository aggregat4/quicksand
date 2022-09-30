package net.aggregat4.quicksand;

import net.aggregat4.dblib.Migrations;

import java.sql.Connection;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

public class QuicksandMigrations implements Migrations {

    @Override
    public Map<Integer, Function<Connection, Integer>> getMigrations() {
        return Collections.emptyMap();
    }

    @Override
    public int getCurrentVersion() {
        return 1;
    }
}
