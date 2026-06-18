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

test('message viewer updates when selecting different headers', async ({ page }) => {
  await waitForDemoInbox(page);

  const headers = page.locator('#messagelist a.emailheader');
  const first = headers.nth(0);
  const second = headers.nth(1);
  const firstSubject = (await first.locator('.subjectline').textContent())?.trim();
  const secondSubject = (await second.locator('.subjectline').textContent())?.trim();
  expect(firstSubject).toBeTruthy();
  expect(secondSubject).toBeTruthy();
  expect(firstSubject).not.toBe(secondSubject);

  const viewerSubject = page.frameLocator('iframe[name="emailviewer"]').locator('#emailsubject h1');

  await first.click();
  await expect(page.locator('#messagepreview')).toBeVisible();
  await expect(viewerSubject).toHaveText(firstSubject);

  await second.click();
  await expect(viewerSubject).toHaveText(secondSubject);

  await first.click();
  await expect(viewerSubject).toHaveText(firstSubject);
});

test('message viewer updates when preview opens from selectedEmailId URL', async ({ page }) => {
  await waitForDemoInbox(page);

  const headers = page.locator('#messagelist a.emailheader');
  const firstId = await headers.nth(0).getAttribute('data-message-id');
  const firstSubject = (await headers.nth(0).locator('.subjectline').textContent())?.trim();
  const secondSubject = (await headers.nth(1).locator('.subjectline').textContent())?.trim();
  expect(firstId).toBeTruthy();

  await page.goto(`/accounts/1?selectedEmailId=${firstId}`);
  await expect(page.locator('#messagepreview')).toBeVisible();

  const viewerSubject = page.frameLocator('iframe[name="emailviewer"]').locator('#emailsubject h1');
  await expect(viewerSubject).toHaveText(firstSubject);

  await headers.nth(1).click();
  await expect(viewerSubject).toHaveText(secondSubject);
});

test('sent folder navigation completes within a reasonable time', async ({ page }) => {
  await waitForDemoInbox(page);

  const sentLink = page.locator('#folderlist a[title="Sent"]');
  await expect(sentLink).toBeVisible();

  await sentLink.click();
  await expect(page).toHaveURL(/\/accounts\/1\/folders\/\d+/, { timeout: 10_000 });
  await expect(page.locator('#messagelist')).toBeVisible({ timeout: 10_000 });
});
