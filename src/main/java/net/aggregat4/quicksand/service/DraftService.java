package net.aggregat4.quicksand.service;

import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.repository.DraftRepository;
import net.aggregat4.quicksand.repository.EmailRepository;

import java.util.Optional;
import java.util.stream.Collectors;

public class DraftService {
    private final DraftRepository draftRepository;
    private final EmailRepository emailRepository;

    public DraftService(DraftRepository draftRepository, EmailRepository emailRepository) {
        this.draftRepository = draftRepository;
        this.emailRepository = emailRepository;
    }

    public Draft createDraft(int accountId) {
        return draftRepository.create(new Draft(0, accountId, DraftType.NEW, Optional.empty(), "", "", "", "", "", false));
    }

    public Optional<Draft> createReplyDraft(int accountId, int sourceMessageId) {
        return emailRepository.findById(sourceMessageId)
                .map(email -> draftRepository.create(new Draft(
                        0,
                        accountId,
                        DraftType.REPLY,
                        Optional.of(sourceMessageId),
                        email.header().getSender().toString(),
                        "",
                        "",
                        prefixedSubject(email.header().subject(), "Re: "),
                        replyBody(email),
                        false)));
    }

    public Optional<Draft> createForwardDraft(int accountId, int sourceMessageId) {
        return emailRepository.findById(sourceMessageId)
                .map(email -> draftRepository.create(new Draft(
                        0,
                        accountId,
                        DraftType.FORWARD,
                        Optional.of(sourceMessageId),
                        "",
                        "",
                        "",
                        prefixedSubject(email.header().subject(), "Fwd: "),
                        forwardBody(email),
                        false)));
    }

    public Optional<Draft> getDraft(int id) {
        return draftRepository.findById(id);
    }

    public Optional<Draft> saveDraft(int id, String to, String cc, String bcc, String subject, String body) {
        return draftRepository.findById(id)
                .map(existing -> {
                    Draft updated = existing.withContent(to, cc, bcc, subject, body);
                    draftRepository.update(updated);
                    return updated;
                });
    }

    public Optional<Draft> queueDraft(int id) {
        return draftRepository.findById(id)
                .map(existing -> {
                    Draft updated = existing.markQueued();
                    draftRepository.update(updated);
                    return updated;
                });
    }

    public boolean deleteDraft(int id) {
        if (draftRepository.findById(id).isEmpty()) {
            return false;
        }
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
}
