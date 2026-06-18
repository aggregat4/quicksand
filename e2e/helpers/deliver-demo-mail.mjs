import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

const IMAP_HOST = '127.0.0.1';
const IMAP_PORT = 44143;
const IMAP_USER = 'testuser';
const IMAP_PASSWORD = 'testpassword';
const DEMO_RECIPIENT = 'testuser@localhost';

// Keep injected mail inside the e2e fixed clock window (2026-03-25) and after
// the latest seeded inbox message so it counts as "new since last view".
const INJECTED_RECEIVED_UTC = '2026-03-25T09:16:00+00:00';

export async function deliverDemoMail({ subject, body = 'Injected for notification test' }) {
  const script = `
import imaplib
from datetime import datetime, timezone
from email.mime.text import MIMEText

received = datetime.fromisoformat(${JSON.stringify(INJECTED_RECEIVED_UTC)})
msg = MIMEText(${JSON.stringify(body)})
msg["Subject"] = ${JSON.stringify(subject)}
msg["From"] = "notify-test@example.com"
msg["To"] = ${JSON.stringify(DEMO_RECIPIENT)}
msg["Date"] = received.strftime("%a, %d %b %Y %H:%M:%S +0000")

conn = imaplib.IMAP4(${JSON.stringify(IMAP_HOST)}, ${IMAP_PORT})
conn.login(${JSON.stringify(IMAP_USER)}, ${JSON.stringify(IMAP_PASSWORD)})
conn.append("INBOX", "", imaplib.Time2Internaldate(received), msg.as_bytes())
conn.logout()
`;

  await execFileAsync('python3', ['-c', script]);
}
