package net.aggregat4.quicksand.webservice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import org.junit.jupiter.api.Test;

class EmailViewerTemplateTest {
  private static final PebbleTemplate EMAIL_VIEWER_TEMPLATE =
      PebbleConfig.getEngine().getTemplate("templates/emailviewer.peb");

  @Test
  void htmlViewerBodyIframeKeepsSearchQuery() {
    Email email =
        new Email(
            new EmailHeader(
                42,
                4242,
                List.of(
                    new Actor(ActorType.SENDER, "from@example.com", Optional.of("Sender")),
                    new Actor(ActorType.TO, "to@example.com", Optional.of("Recipient"))),
                "Subject",
                ZonedDateTime.parse("2026-04-18T10:15:30+02:00[Europe/Berlin]"),
                0,
                ZonedDateTime.parse("2026-04-18T10:15:30+02:00[Europe/Berlin]"),
                0,
                "Excerpt",
                false,
                false,
                false),
            false,
            "<p>HTML body</p>",
            List.of());

    String html =
        PebbleRenderer.renderTemplate(
            Map.of(
                "email",
                email,
                "showImages",
                false,
                "currentQuery",
                Optional.of("fixture term"),
                "readOnly",
                true,
                "outboundStatus",
                "",
                "outboundStatusDetail",
                ""),
            EMAIL_VIEWER_TEMPLATE);

    Matcher matcher = Pattern.compile("id=\"emailbodyframe\"\\s+src=\"([^\"]+)\"").matcher(html);
    assertTrue(matcher.find());
    String iframeSrc =
        URLDecoder.decode(matcher.group(1).replace("&amp;", "&"), StandardCharsets.UTF_8);

    assertTrue(iframeSrc.contains("/emails/42/viewer/body?showImages=false"));
    assertTrue(iframeSrc.contains("query=fixture term"));
    assertFalse(iframeSrc.contains("showImages=true"));
  }
}
