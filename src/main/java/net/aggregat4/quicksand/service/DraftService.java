package net.aggregat4.quicksand.service;

import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.repository.DraftRepository;
import net.aggregat4.quicksand.repository.EmailRepository;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class DraftService {
    private final DraftRepository draftRepository;
    private final EmailRepository emailRepository;
    private final AttachmentService attachmentService;
    private final Clock clock;

    public DraftService(DraftRepository draftRepository, EmailRepository emailRepository, AttachmentService attachmentService, Clock clock) {
        this.draftRepository = draftRepository;
        this.emailRepository = emailRepository;
        this.attachmentService = attachmentService;
        this.clock = clock;
    }

    public Draft createDraft(int accountId) {
        ZonedDateTime now = now();
        return draftRepository.create(new Draft(0, accountId, DraftType.NEW, Optional.empty(), "", "", "", "", "", false, now, now.toEpochSecond()));
    }

    public Optional<Draft> createReplyDraft(int accountId, int sourceMessageId) {
        return emailRepository.findById(sourceMessageId)
                .map(email -> {
                    ZonedDateTime now = now();
                    return draftRepository.create(new Draft(
                            0,
                            accountId,
                            DraftType.REPLY,
                            Optional.of(sourceMessageId),
                            email.header().getSender().toString(),
                            "",
                            "",
                            prefixedSubject(email.header().subject(), "Re: "),
                            replyBody(email),
                            false,
                            now,
                            now.toEpochSecond()));
                });
    }

    public Optional<Draft> createForwardDraft(int accountId, int sourceMessageId) {
        return emailRepository.findById(sourceMessageId)
                .map(email -> {
                    ZonedDateTime now = now();
                    return draftRepository.create(new Draft(
                            0,
                            accountId,
                            DraftType.FORWARD,
                            Optional.of(sourceMessageId),
                            "",
                            "",
                            "",
                            prefixedSubject(email.header().subject(), "Fwd: "),
                            forwardBody(email),
                            false,
                            now,
                            now.toEpochSecond()));
                });
    }

    public Optional<Draft> getDraft(int id) {
        return draftRepository.findById(id);
    }

    public List<EmailHeader> getDraftHeaders(int accountId) {
        return draftRepository.findOpenByAccountId(accountId).stream()
                .map(this::toEmailHeader)
                .toList();
    }

    public Optional<Draft> saveDraft(int id, String to, String cc, String bcc, String subject, String body) {
        return draftRepository.findById(id)
                .map(existing -> {
                    Draft updated = existing.withContent(to, cc, bcc, subject, body, now());
                    draftRepository.update(updated);
                    return updated;
                });
    }

    public Optional<Draft> queueDraft(int id) {
        return draftRepository.findById(id)
                .map(existing -> {
                    Draft updated = existing.markQueued(now());
                    draftRepository.update(updated);
                    return updated;
                });
    }

    public boolean deleteDraft(int id) {
        if (draftRepository.findById(id).isEmpty()) {
            return false;
        }
        attachmentService.deleteDraftAttachments(id);
        draftRepository.delete(id);
        return true;
    }

    private static String prefixedSubject(String subject, String prefix) {
        if (subject == null || subject.isBlank()) {
            return prefix.stripTrailing();
        }
        return subject.regionMatches(true, 0, prefix, 0, prefix.length()) ? subject : prefix + subject;
    }

    private static String replyBody(Email email) {
        String sourceBody = sourceBody(email);
        if (sourceBody.isBlank()) {
            return "\n\nOn %s, %s wrote:\n".formatted(
                    email.header().longFormattedSentDate(),
                    email.header().getSender());
        }
        return "\n\nOn %s, %s wrote:\n%s".formatted(
                email.header().longFormattedSentDate(),
                email.header().getSender(),
                sourceBody.lines().map(line -> "> " + line).collect(Collectors.joining("\n")));
    }

    private static String forwardBody(Email email) {
        String recipients = email.header().getRecipients().stream()
                .map(Actor::toString)
                .collect(Collectors.joining(", "));
        String sourceBody = sourceBody(email);
        return """


                ---------- Forwarded message ----------
                From: %s
                Date: %s
                Subject: %s
                To: %s

                %s
                """.formatted(
                email.header().getSender(),
                email.header().longFormattedSentDate(),
                email.header().subject(),
                recipients,
                sourceBody);
    }

    private static String sourceBody(Email email) {
        if (email.plainText() && email.body() != null) {
            return email.body();
        }
        return Optional.ofNullable(email.header().bodyExcerpt()).orElse("");
    }

    private EmailHeader toEmailHeader(Draft draft) {
        String subject = draft.subject().isBlank() ? "(no subject)" : draft.subject();
        String excerpt = createExcerpt(draft.body());
        return new EmailHeader(
                draft.id(),
                -draft.id(),
                List.of(new Actor(ActorType.SENDER, "draft@local.invalid", Optional.of("Draft"))),
                subject,
                draft.updatedAt(),
                draft.updatedAtEpochSeconds(),
                draft.updatedAt(),
                draft.updatedAtEpochSeconds(),
                excerpt,
                false,
                attachmentService.hasDraftAttachments(draft.id()),
                false);
    }

    private static String createExcerpt(String body) {
        if (body == null || body.isBlank()) {
            return "Unsaved draft";
        }
        String collapsed = body.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= 160) {
            return collapsed;
        }
        return collapsed.substring(0, 157) + "...";
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }
}
