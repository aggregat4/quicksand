import { expect, test } from '@playwright/test';

const DEMO_MESSAGE_COUNT = 273;
const PAGE_SIZE = 100;
const LAST_PAGE_COUNT = 73;
const DESCENDING_GROUPS = ['Today', 'This Week', 'Last Week', 'This Month', 'Last Three Months', 'Older'];
const ASCENDING_GROUPS = [...DESCENDING_GROUPS].reverse();
const HTML_DEMO_SUBJECT = 'HTML demo: Product launch digest';
const DESCENDING_START_SUBJECT = 'Today latest - welcome to the demo inbox';
const BOUNDARY_SUBJECTS = [
  'Today exact - start of day',
  'This week exact - start of week',
  'Last week exact - start of last week',
  'This month exact - start of month',
  'Last three months exact - start of window',
  'Older boundary - one minute before last three months'
];

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

async function inboxPath(page) {
  const href = await page.locator('#folderlist a[title="INBOX"]').getAttribute('href');
  expect(href).toBeTruthy();
  return href;
}

function withQuery(path, params) {
  const search = new URLSearchParams(params);
  return `${path}?${search.toString()}`;
}

async function pageSubjects(page) {
  return await page.locator('#messagelist .subjectline').evaluateAll(nodes =>
    nodes.map(node => node.textContent?.trim() ?? '')
  );
}

async function pageGroupLabels(page) {
  return await page.locator('#messagelist .emailgroup').evaluateAll(nodes =>
    nodes.map(node => node.textContent?.trim() ?? '').filter(Boolean)
  );
}

async function collectSubjectsAcrossPages(page, startUrl) {
  const collected = [];
  await page.goto(startUrl);

  for (let i = 0; i < 10; i += 1) {
    collected.push(...await pageSubjects(page));
    const nextLink = page.locator('#emailpagination a[title="Next"]');
    if ((await nextLink.getAttribute('aria-disabled')) === 'true') {
      return collected;
    }
    await nextLink.click();
  }

  throw new Error('paging did not terminate within the expected number of pages');
}

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
  await waitForDemoInbox(page);

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

  await composerFrame.locator('#email-subject').fill('Draft subject');
  await composerFrame.getByLabel('Email Body').fill('Draft body');
  await composerFrame.getByRole('button', { name: 'Send Email' }).click();

  await expect(composerFrame.locator('#validation-errors')).toContainText("Missing 'To' field");
  await expect(composerFrame.locator('#email-subject')).toHaveValue('Draft subject');
  await expect(composerFrame.getByLabel('Email Body')).toHaveValue('Draft body');

  await composerFrame.locator('#email-to').fill('Alice <alice@example.com>');
  await composerFrame.getByRole('button', { name: 'Send Email' }).click();
  await expect(composerFrame.locator('.info-notification')).toContainText('queued');
});

test('reply and forward create persisted drafts with derived defaults', async ({ page }) => {
  await waitForDemoInbox(page);

  const firstMessage = page.locator('#messagelist a.emailheader').first();
  await firstMessage.click();

  const viewerFrame = page.frameLocator('iframe[name="emailviewer"]');
  await viewerFrame.getByRole('button', { name: 'Reply' }).click();

  const composerFrame = page.frameLocator('#newmail-composer-frame');
  await expect(composerFrame.locator('#email-to')).toHaveValue(/hello@quicksand\.demo/);
  await expect(composerFrame.locator('#email-subject')).toHaveValue(/^Re: Today latest - welcome to the demo inbox$/);
  await expect(composerFrame.getByLabel('Email Body')).toContainText('On ');

  await page.evaluate(() => window.postMessage({ type: 'forward-email', emailId: 1 }, '*'));
  await expect(composerFrame.locator('#email-to')).toHaveValue('');
  await expect(composerFrame.locator('#email-subject')).toHaveValue(/^Fwd: Today latest - welcome to the demo inbox$/);
  await expect(composerFrame.getByLabel('Email Body')).toContainText('Forwarded message');
});

test('new drafts persist headers and body and reopen from the drafts folder', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.getByRole('button', { name: 'New Mail' }).click();
  const composerFrame = page.frameLocator('#newmail-composer-frame');
  await expect(composerFrame.locator('form#save-email-form')).toBeVisible();

  await composerFrame.locator('#email-to').fill('Alice <alice@example.com>');
  await composerFrame.locator('#email-cc').fill('Bob <bob@example.com>');
  await composerFrame.locator('#email-bcc').fill('Carol <carol@example.com>');
  await composerFrame.locator('#email-subject').fill('Drafts folder subject');
  await composerFrame.getByLabel('Email Body').fill('Drafts folder body with enough text to show up as the excerpt');
  await composerFrame.locator('#attachment-upload-input').setInputFiles({
    name: 'draft-note.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('draft attachment body')
  });
  await expect(composerFrame.locator('#draft-attachments')).toContainText('draft-note.txt');
  await expect(composerFrame.locator('#email-subject')).toHaveValue('Drafts folder subject');

  await page.locator('#newmail-composer-dialog .dialogcloser button').click();
  await page.locator('#folderlist a[title="Drafts"]').click();

  await expect(page.locator('#pagination-status')).toContainText('drafts');
  const draftRow = page.locator('#messagelist a.emailheader').filter({
    has: page.locator('.subjectline', { hasText: 'Drafts folder subject' })
  });
  await expect(draftRow).toHaveCount(1);
  await expect(draftRow.locator('.bodyline')).toContainText('Drafts folder body with enough text to show up as the excerpt');
  await draftRow.click();
  await expect(composerFrame.locator('#email-to')).toHaveValue('Alice <alice@example.com>');
  await expect(composerFrame.locator('#email-cc')).toHaveValue('Bob <bob@example.com>');
  await expect(composerFrame.locator('#email-bcc')).toHaveValue('Carol <carol@example.com>');
  await expect(composerFrame.locator('#email-subject')).toHaveValue('Drafts folder subject');
  await expect(composerFrame.getByLabel('Email Body')).toHaveValue('Drafts folder body with enough text to show up as the excerpt');
  const attachmentLink = composerFrame.locator('#draft-attachments a.attachment', { hasText: 'draft-note.txt' });
  await expect(attachmentLink).toHaveCount(1);
  const attachmentHref = await attachmentLink.getAttribute('href');
  expect(attachmentHref).toBeTruthy();
  const attachmentResponse = await page.request.get(attachmentHref);
  expect(attachmentResponse.ok()).toBeTruthy();
  expect(await attachmentResponse.text()).toBe('draft attachment body');

  await page.locator('#newmail-composer-dialog .dialogcloser button').click();
  await page.getByRole('button', { name: 'New Mail' }).click();
  await expect(composerFrame.locator('#email-to')).toHaveValue('');
  await expect(composerFrame.locator('#email-cc')).toHaveValue('');
  await expect(composerFrame.locator('#email-bcc')).toHaveValue('');
  await expect(composerFrame.locator('#email-subject')).toHaveValue('');
  await expect(composerFrame.getByLabel('Email Body')).toHaveValue('');
  await expect(composerFrame.locator('#no-draft-attachments')).toContainText('No attachments yet');
});

test('sending a draft moves it into outbox with attachments', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.getByRole('button', { name: 'New Mail' }).click();
  const composerFrame = page.frameLocator('#newmail-composer-frame');
  await composerFrame.locator('#email-to').fill('Alice <alice@example.com>');
  await composerFrame.locator('#email-subject').fill('Queued outbox subject');
  await composerFrame.getByLabel('Email Body').fill('Queued outbox body');
  await composerFrame.locator('#attachment-upload-input').setInputFiles({
    name: 'outbox-note.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('outbox attachment body')
  });
  await expect(composerFrame.locator('#draft-attachments')).toContainText('outbox-note.txt');

  await composerFrame.getByRole('button', { name: 'Send Email' }).click();
  await expect(composerFrame.locator('.info-notification')).toContainText('queued');

  await page.locator('#folderlist a[title="Outbox"]').click();
  await expect(page.locator('#pagination-status')).toContainText('queued');

  const queuedRow = page.locator('#messagelist a.emailheader').filter({
    has: page.locator('.subjectline', { hasText: 'Queued outbox subject' })
  });
  await expect(queuedRow).toHaveCount(1);
  await queuedRow.click();

  const viewerFrame = page.frameLocator('iframe[name="emailviewer"]');
  await expect(viewerFrame.locator('#emailsubject h1')).toHaveText('Queued outbox subject');
  await expect(viewerFrame.locator('#emailbody pre')).toContainText('Queued outbox body');
  await expect(viewerFrame.locator('#emailattachments')).toContainText('outbox-note.txt');
  await expect(viewerFrame.getByRole('button', { name: 'Reply' })).toHaveCount(0);

  const attachmentHref = await viewerFrame.locator('#emailattachments a.attachment', { hasText: 'outbox-note.txt' }).getAttribute('href');
  expect(attachmentHref).toBeTruthy();
  const attachmentResponse = await page.request.get(attachmentHref);
  expect(attachmentResponse.ok()).toBeTruthy();
  expect(await attachmentResponse.text()).toBe('outbox attachment body');
});

test('descending inbox shows all temporal groups and seeded HTML examples', async ({ page }) => {
  await waitForDemoInbox(page);

  await expect(page.locator('#pagination-status')).toContainText(`${PAGE_SIZE} of ${DEMO_MESSAGE_COUNT}`);
  await expect(page.locator('#messagelist a.emailheader')).toHaveCount(PAGE_SIZE);
  expect(await pageGroupLabels(page)).toEqual(DESCENDING_GROUPS);

  for (const subject of BOUNDARY_SUBJECTS) {
    await expect(page.locator('#messagelist .subjectline', { hasText: subject })).toBeVisible();
  }

  const htmlMessage = page.locator('#messagelist a.emailheader').filter({
    has: page.locator('.subjectline', { hasText: HTML_DEMO_SUBJECT })
  });
  await htmlMessage.first().click();

  const viewerFrame = page.frameLocator('iframe[name="emailviewer"]');
  await expect(viewerFrame.locator('#emailsubject h1')).toHaveText(HTML_DEMO_SUBJECT);
  await expect(viewerFrame.locator('#emailimages')).toHaveText('Enable Images');
  await expect(viewerFrame.locator('#emailbodyframe')).toBeVisible();

  const htmlBodyFrame = viewerFrame.frameLocator('#emailbodyframe');
  await expect(htmlBodyFrame.locator('h1')).toHaveText('Launch Digest');
  await expect(htmlBodyFrame.locator('body')).toContainText('Migration window: Tuesday 09:00 UTC');
});

test('sorting, grouping and paging stay stable in descending and ascending order', async ({ page }) => {
  await waitForDemoInbox(page);
  const inbox = await inboxPath(page);
  const descendingUrl = inbox;
  const ascendingUrl = withQuery(inbox, { sortOrder: 'ASCENDING', pageDirection: 'RIGHT' });

  await page.goto(descendingUrl);
  const descendingFirstPageSubjects = await pageSubjects(page);
  expect(descendingFirstPageSubjects[0]).toBe(DESCENDING_START_SUBJECT);
  expect(descendingFirstPageSubjects).toHaveLength(PAGE_SIZE);
  expect(await pageGroupLabels(page)).toEqual(DESCENDING_GROUPS);

  await page.locator('#emailpagination a[title="End"]').click();
  await expect(page.locator('#messagelist a.emailheader')).toHaveCount(LAST_PAGE_COUNT);
  await expect(page.locator('#pagination-status')).toContainText(`${LAST_PAGE_COUNT} of ${DEMO_MESSAGE_COUNT}`);
  await page.locator('#emailpagination a[title="Beginning"]').click();
  expect(await pageSubjects(page)).toEqual(descendingFirstPageSubjects);

  const descendingSubjects = await collectSubjectsAcrossPages(page, descendingUrl);
  expect(descendingSubjects).toHaveLength(DEMO_MESSAGE_COUNT);
  expect(new Set(descendingSubjects).size).toBe(DEMO_MESSAGE_COUNT);

  await page.goto(ascendingUrl);
  const ascendingFirstPageSubjects = await pageSubjects(page);
  expect(ascendingFirstPageSubjects).toHaveLength(PAGE_SIZE);
  expect(await pageGroupLabels(page)).toEqual(['Older']);

  await page.locator('#emailpagination a[title="End"]').click();
  await expect(page.locator('#messagelist a.emailheader')).toHaveCount(LAST_PAGE_COUNT);
  await expect(page.locator('#pagination-status')).toContainText(`${LAST_PAGE_COUNT} of ${DEMO_MESSAGE_COUNT}`);
  expect(await pageGroupLabels(page)).toEqual(ASCENDING_GROUPS);
  await page.locator('#emailpagination a[title="Beginning"]').click();
  expect(await pageSubjects(page)).toEqual(ascendingFirstPageSubjects);

  const ascendingSubjects = await collectSubjectsAcrossPages(page, ascendingUrl);
  expect(ascendingSubjects).toHaveLength(DEMO_MESSAGE_COUNT);
  expect(new Set(ascendingSubjects).size).toBe(DEMO_MESSAGE_COUNT);
  expect(ascendingSubjects).toEqual([...descendingSubjects].reverse());
});
