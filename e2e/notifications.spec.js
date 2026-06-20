import { expect, test } from '@playwright/test';
import { deliverDemoMail } from './helpers/deliver-demo-mail.mjs';

async function waitForDemoInbox(page) {
  await expect
    .poll(async () => {
      await page.goto('/accounts/1');
      return await page.locator('#messagelist a.emailheader').count();
    }, {
      message: 'expected demo sync to populate at least one message',
      timeout: 30_000
    })
    .toBeGreaterThan(0);
}

test('SSE wake-up shows inbox notification strip after new mail', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.locator('#folderlist a[title="Drafts"]').click();
  await expect(page.locator('#notification-strip')).toBeHidden();

  const subject = `SSE notify ${Date.now()}`;
  await deliverDemoMail({ subject, body: 'Notification test body' });

  await expect.poll(async () => {
    const strip = page.locator('#notification-strip');
    if (!(await strip.isVisible())) {
      return null;
    }
    return await strip.textContent();
  }, { timeout: 30_000 }).toMatch(/new in Inbox/);
});

test('desktop notification opt-in stores preference', async ({ page, context }) => {
  await context.grantPermissions(['notifications']);
  await page.goto('/accounts/1/settings');

  const checkbox = page.getByRole('checkbox', { name: 'Desktop notifications' });
  await expect(checkbox).not.toBeChecked();

  await checkbox.check();
  await expect(checkbox).toBeChecked();
  await expect(page.locator('#desktop-notifications-status')).toContainText('enabled');

  expect(await page.evaluate(() => localStorage.getItem('quicksand.desktopNotifications'))).toBe('true');

  await checkbox.uncheck();
  await expect(checkbox).not.toBeChecked();
  expect(await page.evaluate(() => localStorage.getItem('quicksand.desktopNotifications'))).toBeNull();
});
