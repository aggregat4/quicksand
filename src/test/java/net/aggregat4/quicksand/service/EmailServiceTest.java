package net.aggregat4.quicksand.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.jobs.InMemoryEmailRepository;
import org.junit.jupiter.api.Test;

public class EmailServiceTest {

  @Test
  void updateReadFlipsFlagAndLeavesStarredUnchanged() {
    InMemoryEmailRepository repository = new InMemoryEmailRepository();
    EmailHeader header =
        new EmailHeader(
            1,
            100L,
            List.of(new Actor(ActorType.SENDER, "alice@example.com", Optional.of("Alice"))),
            "Subject",
            ZonedDateTime.now(),
            0L,
            ZonedDateTime.now(),
            0L,
            "Excerpt",
            true,
            false,
            false);
    Email email = new Email(header, true, "Body", Collections.emptyList());
    repository.addMessage(1, email);

    EmailService service = new EmailService(repository);

    service.updateRead(1, true);

    Email updated = repository.findById(1).orElseThrow();
    assertTrue(updated.header().read());
    assertTrue(updated.header().starred());

    service.updateRead(1, false);

    updated = repository.findById(1).orElseThrow();
    assertFalse(updated.header().read());
    assertTrue(updated.header().starred());
  }
}
