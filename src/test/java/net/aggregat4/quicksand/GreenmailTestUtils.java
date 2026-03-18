package net.aggregat4.quicksand;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;

public class GreenmailTestUtils {

    public static GreenMailExtension configureTestGreenMailExtension() {
        return new GreenMailExtension(ServerSetup.dynamicPort(ServerSetupTest.SMTP_IMAP));
    }
}
