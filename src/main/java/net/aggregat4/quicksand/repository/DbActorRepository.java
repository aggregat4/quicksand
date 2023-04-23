package net.aggregat4.quicksand.repository;

import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Actor;

import javax.sql.DataSource;

public class DbActorRepository {

    private final DataSource ds;

    public DbActorRepository(DataSource ds) {
        this.ds = ds;
    }

    public void saveActor(int messageId, Actor actor) {
        DbUtil.withPreparedStmtConsumer(ds, """
                INSERT INTO actors (message_id, type, name, email_address) VALUES (?, ?, ?, ?)
                """, stmt -> {
            stmt.setInt(1, messageId);
            stmt.setInt(2, actor.type().getValue());
            stmt.setString(3, actor.name().orElseGet(() -> null));
            stmt.setString(4, actor.emailAddress());
            stmt.executeUpdate();
        });
    }

}
