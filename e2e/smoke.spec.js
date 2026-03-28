import { expect, test } from '@playwright/test';

test('home page renders and static assets are reachable', async ({ page, request }) => {
  const cssResponse = await request.get('/css/base.css');
  expect(cssResponse.ok()).toBeTruthy();

  await page.goto('/');

  await expect(page).toHaveTitle(/Quicksand/);
  await expect(page.locator('#apptitle')).toHaveText('Quicksand E-Mail Home');
  await expect(page.locator('main')).toContainText('Hello World!');
});

test('account page supports message preview and composer dialogs', async ({ page }) => {
  await page.goto('/accounts/1');

  await expect
    .poll(async () => {
      await page.goto('/accounts/1');
      return await page.locator('#messagelist a.emailheader').count();
    }, {
      message: 'expected demo sync to populate at least one message',
      timeout: 30_000
    })
    .toBeGreaterThan(0);

  const firstMessage = page.locator('#messagelist a.emailheader').first();
  await firstMessage.click();

  const messagePreview = page.locator('#messagepreview');
  await expect(messagePreview).toBeVisible();

  const viewerFrame = page.frameLocator('iframe[name="emailviewer"]');
  await expect(viewerFrame.locator('#emailsubject h1')).toBeVisible();
  await expect(viewerFrame.getByRole('button', { name: 'Reply' })).toBeVisible();

  await page.getByRole('button', { name: 'New Mail' }).click();

  const composerDialog = page.locator('#newmail-composer-dialog');
  await expect(composerDialog).toBeVisible();

  const composerFrame = page.frameLocator('#newmail-composer-frame');
  await expect(composerFrame.locator('form#save-email-form')).toBeVisible();
  await expect(composerFrame.getByLabel('Email Body')).toBeVisible();
});
