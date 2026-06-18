import { expect, test } from '@playwright/test';

async function waitForDemoInbox(page) {
  await expect
    .poll(async () => {
      await page.goto('/accounts/1');
      return await page.locator('#messagelist a.emailheader').count();
    }, {
      message: 'expected demo sync to populate at least one message',
      timeout: 30_000
    })
    .toBeGreaterThan(1);
}

async function markUnread(page, messageId, referer) {
  const response = await page.request.post('/emails/selection', {
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Referer: referer
    },
    data: `email_select=${messageId}&email_action_mark_unread=Mark+Unread`
  });
  expect(response.status(), await response.text()).toBeLessThan(400);
}

function assertNoDatabaseError(response, body) {
  expect(response.status(), body).toBeLessThan(500);
  expect(body).not.toMatch(/SQLITE_BUSY|database is locked/i);
}

test('concurrent mark read requests do not surface database errors', async ({ page }) => {
  await waitForDemoInbox(page);

  const referer = page.url();
  const messageId = await page.locator('#messagelist a.emailheader').first().getAttribute('data-message-id');
  expect(messageId).toBeTruthy();

  await markUnread(page, messageId, referer);

  const requests = [
    ...Array.from({ length: 8 }, () => page.request.post(`/emails/${messageId}/read`)),
    page.request.post('/emails/selection', {
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        Referer: referer
      },
      data: `email_select=${messageId}&email_action_mark_read=Mark+Read`
    })
  ];

  const responses = await Promise.all(requests);
  for (const response of responses) {
    assertNoDatabaseError(response, await response.text());
  }
});

test('preview open plus toolbar mark read does not surface database errors', async ({ page }) => {
  await waitForDemoInbox(page);

  const referer = page.url();
  const firstRow = page.locator('#messagelist a.emailheader').first();
  const messageId = await firstRow.getAttribute('data-message-id');
  expect(messageId).toBeTruthy();
  await markUnread(page, messageId, referer);
  await page.reload();
  await expect(firstRow).not.toHaveClass(/read/);

  const serverErrors = [];
  page.on('response', async (response) => {
    const url = response.url();
    if (response.status() >= 500 && (url.includes('/read') || url.includes('/emails/selection'))) {
      serverErrors.push(`${response.status()} ${url} :: ${await response.text()}`);
    }
  });

  await firstRow.click();
  await expect(page.locator('#messagepreview')).toBeVisible();

  await page.locator('#selected-email-actions button[name="email_action_mark_read"]').click();
  await expect(page).toHaveURL(/selectedEmailId=/);
  await expect(firstRow).toHaveClass(/read/);

  expect(serverErrors, 'mark-read endpoints should not return 5xx during overlapping writes').toEqual([]);
});
