import { expect, test } from '@playwright/test';

test('home page renders and static assets are reachable', async ({ page, request }) => {
  const cssResponse = await request.get('/css/base.css');
  expect(cssResponse.ok()).toBeTruthy();

  await page.goto('/');

  await expect(page).toHaveTitle(/Quicksand/);
  await expect(page.locator('#apptitle')).toHaveText('Quicksand E-Mail Home');
  await expect(page.locator('main')).toContainText('Hello World!');
});

test('missing viewer ids return 404', async ({ request }) => {
  const response = await request.get('/emails/999999/viewer');
  expect(response.status()).toBe(404);
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
  const selectedSubject = (await firstMessage.locator('.subjectline').textContent())?.trim();
  await firstMessage.click();

  const messagePreview = page.locator('#messagepreview');
  await expect(messagePreview).toBeVisible();

  const viewerFrame = page.frameLocator('iframe[name="emailviewer"]');
  await expect(viewerFrame.locator('#emailsubject h1')).toBeVisible();
  await expect(viewerFrame.locator('#emailsubject h1')).toHaveText(selectedSubject ?? '');
  await expect(viewerFrame.locator('#emailbody pre')).not.toBeEmpty();
  await expect(viewerFrame.getByRole('button', { name: 'Reply' })).toBeVisible();

  await page.getByRole('button', { name: 'New Mail' }).click();

  const composerDialog = page.locator('#newmail-composer-dialog');
  await expect(composerDialog).toBeVisible();

  const composerFrame = page.frameLocator('#newmail-composer-frame');
  await expect(composerFrame.locator('form#save-email-form')).toBeVisible();
  await expect(composerFrame.getByLabel('Email Body')).toBeVisible();
});

test('account browsing and paging stay consistent for inbox', async ({ page }) => {
  await page.goto('/accounts/1');

  await expect(page.locator('#pagination-status')).toContainText('of 120');
  await expect(page.locator('#messagelist a.emailheader')).toHaveCount(100);

  const initialSubjects = await page.locator('#messagelist .subjectline').evaluateAll(nodes =>
    nodes.map(node => node.textContent?.trim() ?? '')
  );
  expect(initialSubjects.length).toBe(100);

  const inboxLink = page.locator('#folderlist a[title="INBOX"]');
  await inboxLink.click();
  await expect(page).toHaveURL(/\/accounts\/1\/folders\/\d+/);

  const reloadedSubjects = await page.locator('#messagelist .subjectline').evaluateAll(nodes =>
    nodes.map(node => node.textContent?.trim() ?? '')
  );
  expect(reloadedSubjects).toEqual(initialSubjects);

  const nextLink = page.locator('#emailpagination a[title="Next"]');
  await expect(nextLink).not.toHaveAttribute('aria-disabled', 'true');
  await nextLink.click();

  await expect(page).toHaveURL(/offsetReceivedTimestamp=/);
  await expect(page.locator('#messagelist a.emailheader')).toHaveCount(20);
  await expect(page.locator('#pagination-status')).toContainText('older than');
  await expect(page.locator('#pagination-status')).not.toContainText(/\b\d{10}\b/);
  const nextPageSubjects = await page.locator('#messagelist .subjectline').evaluateAll(nodes =>
    nodes.map(node => node.textContent?.trim() ?? '')
  );
  expect(nextPageSubjects.length).toBeGreaterThan(0);
  expect(nextPageSubjects).not.toEqual(initialSubjects);

  const previousLink = page.locator('#emailpagination a[title="Previous"]');
  await expect(previousLink).not.toHaveAttribute('aria-disabled', 'true');
  await previousLink.click();

  await expect(page).toHaveURL(/\/accounts\/1\/folders\/\d+/);
  const previousPageSubjects = await page.locator('#messagelist .subjectline').evaluateAll(nodes =>
    nodes.map(node => node.textContent?.trim() ?? '')
  );
  expect(previousPageSubjects).toEqual(initialSubjects);
});
