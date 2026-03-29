package net.aggregat4.quicksand.repository;

import net.aggregat4.quicksand.domain.OutboundMessage;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

public interface OutboundMessageRepository {
    OutboundMessage create(Connection con, OutboundMessage outboundMessage);

    Optional<OutboundMessage> findById(int id);

    List<OutboundMessage> findByAccountId(int accountId);
}
