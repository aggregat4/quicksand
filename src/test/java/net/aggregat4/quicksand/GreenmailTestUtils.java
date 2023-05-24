package net.aggregat4.quicksand;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;

public class GreenmailTestUtils {

    public static GreenMailExtension configureTestGreenMailExtension() {
        return new GreenMailExtension(new ServerSetup[]{
                new ServerSetup(25 + 4000, null, ServerSetup.PROTOCOL_SMTP),
                new ServerSetup(143 + 4000, null, ServerSetup.PROTOCOL_IMAP)});
    }
}
