package net.aggregat4.quicksand.repository;

import net.aggregat4.quicksand.domain.OutboundMessage;
import net.aggregat4.quicksand.domain.OutboundMessageStatus;

import java.sql.Connection;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboundMessageRepository {
    OutboundMessage create(Connection con, OutboundMessage outboundMessage);

    Optional<OutboundMessage> findById(int id);

    List<OutboundMessage> findByAccountId(int accountId);

    List<OutboundMessage> findByStatus(OutboundMessageStatus status);

    void markSent(int id, ZonedDateTime sentAt);

    void markFailed(int id, String lastError);
}
