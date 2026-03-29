package net.aggregat4.quicksand.service;

import jakarta.mail.internet.InternetAddress;
import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.OutboundMessage;
import net.aggregat4.quicksand.domain.OutboundMessageStatus;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbDraftRepository;
import net.aggregat4.quicksand.repository.OutboundMessageRepository;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class OutboundMessageService {
    private final DataSource ds;
    private final DbAccountRepository accountRepository;
    private final DbDraftRepository draftRepository;
    private final DbAttachmentRepository attachmentRepository;
    private final OutboundMessageRepository outboundMessageRepository;
    private final Clock clock;

    public OutboundMessageService(
            DataSource ds,
            DbAccountRepository accountRepository,
            DbDraftRepository draftRepository,
            DbAttachmentRepository attachmentRepository,
            OutboundMessageRepository outboundMessageRepository,
            Clock clock) {
        this.ds = ds;
        this.accountRepository = accountRepository;
        this.draftRepository = draftRepository;
        this.attachmentRepository = attachmentRepository;
        this.outboundMessageRepository = outboundMessageRepository;
        this.clock = clock;
    }

    public Optional<OutboundMessage> queueDraftForDelivery(int draftId) {
        return DbUtil.withConFunction(ds, con -> {
            boolean previousAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                Optional<net.aggregat4.quicksand.domain.Draft> draft = draftRepository.findById(con, draftId);
                if (draft.isEmpty()) {
                    con.rollback();
                    return Optional.empty();
                }
                Account account = accountRepository.getAccount(draft.get().accountId());
                OutboundMessage created = outboundMessageRepository.create(con, toOutboundMessage(draft.get(), account));
                attachmentRepository.moveDraftAttachmentsToOutboundMessage(con, draftId, created.id());
                draftRepository.delete(con, draftId);
                con.commit();
                return Optional.of(created);
            } catch (RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(previousAutoCommit);
            }
        });
    }

    public List<EmailHeader> getQueuedHeaders(int accountId) {
        return outboundMessageRepository.findByAccountId(accountId).stream()
                .map(this::toEmailHeader)
                .toList();
    }

    public Optional<Email> getQueuedMessage(int id) {
        return outboundMessageRepository.findById(id)
                .map(message -> new Email(
                        toEmailHeader(message),
                        true,
                        message.body(),
                        attachmentRepository.findByOutboundMessageId(message.id())));
    }

    public Optional<OutboundMessage> findOutboundMessage(int id) {
        return outboundMessageRepository.findById(id);
    }

    private OutboundMessage toOutboundMessage(net.aggregat4.quicksand.domain.Draft draft, Account account) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        return new OutboundMessage(
                0,
                draft.accountId(),
                draft.sourceMessageId(),
                account.name(),
                primaryAccountAddress(account),
                draft.to(),
                draft.cc(),
                draft.bcc(),
                draft.subject(),
                draft.body(),
                OutboundMessageStatus.QUEUED,
                0,
                Optional.empty(),
                now,
                Optional.empty(),
                now.toEpochSecond());
    }

    private EmailHeader toEmailHeader(OutboundMessage message) {
        String subject = message.subject() == null || message.subject().isBlank() ? "(no subject)" : message.subject();
        return new EmailHeader(
                message.id(),
                -message.id(),
                actors(message),
                subject,
                message.queuedAt(),
                message.queuedAtEpochSeconds(),
                message.queuedAt(),
                message.queuedAtEpochSeconds(),
                createStatusExcerpt(message),
                false,
                !attachmentRepository.findByOutboundMessageId(message.id()).isEmpty(),
                true);
    }

    private List<Actor> actors(OutboundMessage message) {
        List<Actor> actors = new ArrayList<>();
        actors.add(new Actor(ActorType.SENDER, message.fromAddress(), Optional.ofNullable(blankToNull(message.fromName()))));
        actors.addAll(parseActors(message.to(), ActorType.TO));
        actors.addAll(parseActors(message.cc(), ActorType.CC));
        actors.addAll(parseActors(message.bcc(), ActorType.BCC));
        return actors;
    }

    private static List<Actor> parseActors(String rawValue, ActorType type) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }
        try {
            InternetAddress[] addresses = InternetAddress.parseHeader(rawValue, false);
            List<Actor> actors = new ArrayList<>();
            for (InternetAddress address : addresses) {
                actors.add(new Actor(type, address.getAddress(), Optional.ofNullable(address.getPersonal())));
            }
            return actors;
        } catch (Exception ignored) {
            return List.of(new Actor(type, rawValue.trim(), Optional.empty()));
        }
    }

    private static String primaryAccountAddress(Account account) {
        if (account.smtpUsername() != null && account.smtpUsername().contains("@")) {
            return account.smtpUsername();
        }
        if (account.imapUsername() != null && account.imapUsername().contains("@")) {
            return account.imapUsername();
        }
        return "outbox@local.invalid";
    }

    private static String createExcerpt(String body) {
        if (body == null || body.isBlank()) {
            return "Queued message";
        }
        String collapsed = body.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= 160) {
            return collapsed;
        }
        return collapsed.substring(0, 157) + "...";
    }

    private static String createStatusExcerpt(OutboundMessage message) {
        String excerpt = createExcerpt(message.body());
        return switch (message.status()) {
            case QUEUED -> "Queued: " + excerpt;
            case SENT -> "Sent: " + excerpt;
            case FAILED -> "Failed: " + message.lastError().orElse(excerpt);
        };
    }

    public static String formatStatus(OutboundMessage message) {
        return switch (message.status()) {
            case QUEUED -> "Queued for delivery";
            case SENT -> "Sent";
            case FAILED -> "Delivery failed";
        };
    }

    public static String formatStatusDetail(OutboundMessage message) {
        return switch (message.status()) {
            case QUEUED -> "Awaiting SMTP delivery.";
            case SENT -> message.sentAt()
                    .map(sentAt -> "Delivered to SMTP at " + sentAt.format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)))
                    .orElse("Delivered to SMTP.");
            case FAILED -> message.lastError().map(error -> "Last delivery error: " + error).orElse("Last delivery attempt failed.");
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
