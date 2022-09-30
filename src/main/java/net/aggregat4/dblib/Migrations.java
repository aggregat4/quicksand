package net.aggregat4.dblib;

import java.sql.Connection;
import java.util.Map;
import java.util.function.Function;

public interface Migrations {

    Map<Integer, Function<Connection, Integer>> getMigrations();
    int getCurrentVersion();
}
