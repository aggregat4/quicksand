import { defineConfig } from '@playwright/test';

const port = 43173;
const baseURL = `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: {
    timeout: 10_000
  },
  reporter: 'list',
  use: {
    baseURL,
    browserName: 'chromium',
    headless: true,
    launchOptions: {
      executablePath: '/usr/bin/chromium',
      args: ['--no-sandbox']
    }
  },
  webServer: {
    command: `java -Dserver.host=127.0.0.1 -Dserver.port=${port} -Ddemo.enabled=true -Ddemo.smtp_port=44025 -Ddemo.imap_port=44143 -Ddemo.account.smtp_port=44025 -Ddemo.account.imap_port=44143 -Dmail_fetcher.enabled=true -Dmail_fetcher.period_seconds=1 -Ddatabase.path=./target/e2e-db/quicksand.sqlite -jar target/quicksand.jar`,
    url: baseURL,
    reuseExistingServer: false,
    timeout: 120_000
  }
});
