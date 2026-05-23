package net.aggregat4.quicksand.demo;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.helidon.config.Config;
import java.time.Clock;

public final class DemoMailServer {
  private DemoMailServer() {}

  public static GreenMail start(Config demoConfig, Clock clock) {
    int smtpPort = demoConfig.get("smtp_port").asInt().orElse(25 + 4000);
    int imapPort = demoConfig.get("imap_port").asInt().orElse(143 + 4000);
    int seedCount = demoConfig.get("seed_count").asInt().orElse(273);
    GreenMail greenMail =
        new GreenMail(
            new ServerSetup[] {
              new ServerSetup(smtpPort, null, ServerSetup.PROTOCOL_SMTP),
              new ServerSetup(imapPort, null, ServerSetup.PROTOCOL_IMAP)
            });
    greenMail.start();
    DemoMessageSeeder.deliverDemoMessages(greenMail, seedCount, clock);
    return greenMail;
  }
}
