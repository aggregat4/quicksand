import { expect, test } from '@playwright/test';

const DEMO_MESSAGE_COUNT = 273;
const PAGE_SIZE = 100;
const LAST_PAGE_COUNT = 73;
const DUPLICATE_SEARCH_COUNT = 256;
const DUPLICATE_SEARCH_LAST_PAGE_COUNT = 56;
const DESCENDING_GROUPS = ['Today', 'This Week', 'Last Week', 'This Month'];
const DESCENDING_END_GROUPS = ['Last Three Months', 'Older'];
const ASCENDING_END_GROUPS = ['Last Week', 'This Week', 'Today'];
const HTML_DEMO_SUBJECTS = [
  'HTML demo: Product launch digest',
  'HTML demo: Summer Sale — up to 50% off',
  'HTML demo: Your flight confirmation QR4217',
  'HTML demo: Monthly invoice — Acme Corp',
  'HTML demo: Security alert — new device sign-in'
];
const DESCENDING_START_SUBJECT = 'Today latest - welcome to the demo inbox';
const FIRST_PAGE_BOUNDARY_SUBJECTS = [
  'Today exact - start of day',
  'This week exact - start of week',
  'Last week boundary - one minute before this week'
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

test('home redirects to the only configured account and static assets are reachable', async ({ page, request }) => {
  const cssResponse = await request.get('/css/base.css');
  expect(cssResponse.ok()).toBeTruthy();

  await expect.poll(async () => {
    await page.goto('/');
    return page.url();
  }, {
    message: 'expected demo bootstrap to finish before home redirect',
    timeout: 30_000
  }).toMatch(/\/accounts\/1(\/folders\/\d+)?$/);
  await expect(page).toHaveTitle(/Greenmail Test Account/);
  await expect(page.locator('#apptitle')).toHaveText('Greenmail Test Account');
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

  const folderListBox = await page.locator('#folderlist').boundingBox();
  const messageListBox = await page.locator('#messagelist').boundingBox();
  const messagePreviewBox = await messagePreview.boundingBox();
  expect(folderListBox?.x).toBeGreaterThanOrEqual(0);
  expect(messageListBox?.x).toBeGreaterThanOrEqual((folderListBox?.x ?? 0) + (folderListBox?.width ?? 0));
  expect(messagePreviewBox?.x).toBeGreaterThanOrEqual((messageListBox?.x ?? 0) + (messageListBox?.width ?? 0));

  const viewerFrame = page.frameLocator('iframe[name="emailviewer"]');
  await expect(viewerFrame.locator('#emailsubject h1')).toBeVisible();
  await expect(viewerFrame.locator('#emailsubject h1')).toHaveText(selectedSubject ?? '');
  await expect(viewerFrame.locator('#emailbody pre')).not.toBeEmpty();
  await expect(viewerFrame.getByRole('button', { name: 'Reply' })).toBeVisible();

  await page.getByRole('button', { name: 'New Mail' }).click();

  const composerDialog = page.locator('#newmail-composer-dialog');
  await expect(composerDialog).toBeVisible();

  const composerFrame = page.frameLocator('#newmail-composer-frame');
  await expect(composerFrame.locator('#composer-title')).toHaveText('New message');
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

test('narrow account layout keeps message rows readable with preview open', async ({ page }) => {
  await page.setViewportSize({ width: 700, height: 800 });
  await waitForDemoInbox(page);

  await page.locator('#messagelist a.emailheader').first().click();
  await expect(page.locator('#messagepreview')).toBeVisible();

  const messageListBox = await page.locator('#messagelist').boundingBox();
  const messagePreviewBox = await page.locator('#messagepreview').boundingBox();
  const firstRowBox = await page.locator('#messagelist a.emailheader').first().boundingBox();
  const firstDateBox = await page.locator('#messagelist a.emailheader .date-and-actions').first().boundingBox();

  expect(messageListBox?.x).toBeGreaterThanOrEqual(0);
  expect(firstRowBox?.x).toBeGreaterThanOrEqual(messageListBox?.x ?? 0);
  expect(firstRowBox?.width).toBeLessThanOrEqual(messageListBox?.width ?? 0);
  expect(firstDateBox?.x).toBeGreaterThanOrEqual(firstRowBox?.x ?? 0);
  expect(firstDateBox?.x + firstDateBox?.width).toBeLessThanOrEqual(firstRowBox?.x + firstRowBox?.width);
  expect(messagePreviewBox?.y).toBeGreaterThanOrEqual((messageListBox?.y ?? 0) + (messageListBox?.height ?? 0));
});

test('reply and forward create persisted drafts with derived defaults', async ({ page }) => {
  await waitForDemoInbox(page);

  const firstMessage = page.locator('#messagelist a.emailheader').first();
  const messageId = await firstMessage.getAttribute('data-message-id');
  expect(messageId).toBeTruthy();
  await firstMessage.click();

  const viewerFrame = page.frameLocator('iframe[name="emailviewer"]');
  await viewerFrame.getByRole('button', { name: 'Reply' }).click();

  const composerFrame = page.frameLocator('#newmail-composer-frame');
  await expect(composerFrame.locator('#email-to')).toHaveValue(/hello@quicksand\.demo/);
  await expect(composerFrame.locator('#email-subject')).toHaveValue(/^Re: Today latest - welcome to the demo inbox$/);
  await expect(composerFrame.getByLabel('Email Body')).toContainText('On ');

  await page.evaluate((id) => window.postMessage({ type: 'forward-email', emailId: Number(id) }, '*'), messageId);
  await expect(composerFrame.locator('#email-to')).toHaveValue('');
  await expect(composerFrame.locator('#email-subject')).toHaveValue(/^Fwd: Today latest - welcome to the demo inbox$/);
  await expect(composerFrame.getByLabel('Email Body')).toContainText('Forwarded message');
});

test('new drafts persist headers and body and reopen from the drafts folder', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.getByRole('button', { name: 'New Mail' }).click();
  const composerFrame = page.frameLocator('#newmail-composer-frame');
  await expect(composerFrame.locator('#composer-title')).toHaveText('New message');
  await expect(composerFrame.locator('form#save-email-form')).toBeVisible();

  await composerFrame.locator('#email-to').fill('Alice <alice@example.com>');
  await composerFrame.locator('#toggle-cc-bcc').click();
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

  await composerFrame.locator('.composer-close-button').click();
  await page.locator('#folderlist a[title="Drafts"]').click();

  await expect(page.locator('#pagination-status')).toContainText('draft');
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

  await composerFrame.locator('.composer-close-button').click();
  await page.getByRole('button', { name: 'New Mail' }).click();
  await expect(composerFrame.locator('#email-to')).toHaveValue('');
  await expect(composerFrame.locator('#email-cc')).toHaveValue('');
  await expect(composerFrame.locator('#email-bcc')).toHaveValue('');
  await expect(composerFrame.locator('#email-subject')).toHaveValue('');
  await expect(composerFrame.getByLabel('Email Body')).toHaveValue('');
  await expect(composerFrame.locator('#no-draft-attachments')).toContainText('No attachments yet');
  await composerFrame.locator('.composer-close-button').click();
});

test('sending a draft moves it into outbox with attachments', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.getByRole('button', { name: 'New Mail' }).click();
  const composerFrame = page.frameLocator('#newmail-composer-frame');
  await expect(composerFrame.locator('#composer-title')).toHaveText('New message');
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
  await expect(page.locator('#pagination-status')).toContainText('outgoing');

  const queuedRow = page.locator('#messagelist a.emailheader').filter({
    has: page.locator('.subjectline', { hasText: 'Queued outbox subject' })
  });
  await expect(queuedRow).toHaveCount(1);

  await queuedRow.click();
  const viewerFrame = page.frameLocator('iframe[name="emailviewer"]');
  await expect(viewerFrame.locator('#emailsubject h1')).toHaveText('Queued outbox subject');
  await expect(viewerFrame.locator('#emailstatus')).toContainText('Queued');
  await expect(viewerFrame.locator('#emailbody pre')).toContainText('Queued outbox body');
  await expect(viewerFrame.locator('#emailattachments')).toContainText('outbox-note.txt');

  const attachmentHref = await viewerFrame.locator('#emailattachments a.attachment', { hasText: 'outbox-note.txt' }).getAttribute('href');
  expect(attachmentHref).toBeTruthy();
  const attachmentResponse = await page.request.get(attachmentHref);
  expect(attachmentResponse.ok()).toBeTruthy();
  expect(await attachmentResponse.text()).toBe('outbox attachment body');

  await expect.poll(async () => {
    await page.locator('#folderlist a[title="Outbox"]').click();
    await page.reload();
    return await queuedRow.count();
  }, { timeout: 30_000 }).toBe(0);
});

test('search finds targeted messages inside the existing account viewer flow', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.locator('#searchemailinput').fill('Launch Digest');
  await page.locator('#searchemailform').getByRole('button', { name: 'Search' }).click();

  await expect(page).toHaveURL(/\/accounts\/1\/search\?query=Launch\+Digest/);
  await expect(page.locator('#searchemailinput')).toHaveValue('Launch Digest');
  await expect(page.locator('#clearsearchlink')).toBeVisible();
  await expect(page.locator('#pagination-status')).toContainText('1 message');
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', /1 message total/);
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', /Page 1 of 1/);
  await expect(page.locator('#messagelist .emailgroup')).toHaveCount(0);

  const resultRow = page.locator('#messagelist a.emailheader').filter({
    has: page.locator('.subjectline', { hasText: 'HTML demo: Product launch digest' })
  });
  await expect(resultRow).toHaveCount(1);
  await expect(resultRow.locator('.fromname')).toContainText('launches@example.com');
  expect(await resultRow.locator('mark').count()).toBeGreaterThanOrEqual(3);
  await resultRow.click();

  const viewerFrame = page.frameLocator('iframe[name="emailviewer"]');
  await expect(viewerFrame.locator('#emailsubject h1')).toHaveText('HTML demo: Product launch digest');
  await expect(viewerFrame.locator('#emailsubject mark')).toHaveCount(2);

  await page.locator('#clearsearchlink').click();
  await expect(page).toHaveURL('/accounts/1');
  await expect(page.locator('#searchemailinput')).toHaveValue('');
});

test('submitting an empty search query exits search mode', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.locator('#searchemailinput').fill('Launch Digest');
  await page.locator('#searchemailform').getByRole('button', { name: 'Search' }).click();
  await expect(page).toHaveURL(/\/accounts\/1\/search\?query=Launch\+Digest/);

  await page.locator('#searchemailinput').fill('');
  await page.locator('#searchemailform').getByRole('button', { name: 'Search' }).click();

  await expect(page).toHaveURL('/accounts/1');
  await expect(page.locator('#searchemailinput')).toHaveValue('');
  await expect(page.locator('#clearsearchlink')).toHaveCount(0);
  await expect(page.locator('#messagelist .emailgroup')).not.toHaveCount(0);
});

test('search paging stays stable for multi-page result sets', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.goto('/accounts/1/search?query=Duplicate%20timestamp%20sample');

  await expect(page.locator('#searchemailinput')).toHaveValue('Duplicate timestamp sample');
  await expect(page.locator('#pagination-status')).toContainText(`${PAGE_SIZE} messages`);
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', new RegExp(`${DUPLICATE_SEARCH_COUNT} messages total`));
  await expect(page.locator('#messagelist a.emailheader')).toHaveCount(PAGE_SIZE);
  await expect(page.locator('#messagelist .emailgroup')).toHaveCount(0);

  const firstPageSubjects = await pageSubjects(page);
  await page.locator('#emailpagination a[title="Next"]').click();
  await expect(page.locator('#pagination-status')).toContainText(`${PAGE_SIZE} messages`);
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', new RegExp(`${DUPLICATE_SEARCH_COUNT} messages total`));
  expect(await pageSubjects(page)).not.toEqual(firstPageSubjects);

  await page.locator('#emailpagination a[title="End"]').click();
  await expect(page.locator('#messagelist a.emailheader')).toHaveCount(DUPLICATE_SEARCH_LAST_PAGE_COUNT);
  await expect(page.locator('#pagination-status')).toContainText(`${DUPLICATE_SEARCH_LAST_PAGE_COUNT} messages`);
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', new RegExp(`${DUPLICATE_SEARCH_COUNT} messages total`));
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', /Page 3 of 3/);

  await page.locator('#emailpagination a[title="Beginning"]').click();
  expect(await pageSubjects(page)).toEqual(firstPageSubjects);
});

test('descending inbox shows all temporal groups and seeded HTML examples', async ({ page }) => {
  await waitForDemoInbox(page);

  await expect(page.locator('#pagination-status')).toContainText(`${PAGE_SIZE} messages`);
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', new RegExp(`${DEMO_MESSAGE_COUNT} messages total`));
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', /Page 1 of 3/);
  await expect(page.locator('#messagelist a.emailheader')).toHaveCount(PAGE_SIZE);
  expect(await pageGroupLabels(page)).toEqual(DESCENDING_GROUPS);

  for (const subject of FIRST_PAGE_BOUNDARY_SUBJECTS) {
    await expect(page.locator('#messagelist .subjectline', { hasText: subject })).toBeVisible();
  }

  // Verify at least one HTML demo is visible on the first page
  for (const subject of HTML_DEMO_SUBJECTS) {
    const count = await page.locator('#messagelist .subjectline', { hasText: subject }).count();
    if (count > 0) {
      const htmlMessage = page.locator('#messagelist a.emailheader').filter({
        has: page.locator('.subjectline', { hasText: subject })
      });
      await htmlMessage.first().click();
      break;
    }
  }

  const viewerFrame = page.frameLocator('iframe[name="emailviewer"]');
  await expect(viewerFrame.locator('#emailsubject h1')).toHaveText(/HTML demo:/);
  await expect(viewerFrame.locator('#emailimages')).toHaveText('Enable Images');
  await expect(viewerFrame.locator('#emailbodyframe')).toBeVisible();

  const htmlViewerLayout = await page.frame({ name: 'emailviewer' }).evaluate(() => {
    const body = document.querySelector('#emailbody').getBoundingClientRect();
    const frame = document.querySelector('#emailbodyframe').getBoundingClientRect();
    return {
      bodyHeight: body.height,
      frameHeight: frame.height
    };
  });
  expect(htmlViewerLayout.bodyHeight).toBeGreaterThan(300);
  expect(htmlViewerLayout.frameHeight).toBeGreaterThan(300);
  expect(htmlViewerLayout.frameHeight).toBeGreaterThan(htmlViewerLayout.bodyHeight - 40);

  const htmlBodyFrame = viewerFrame.frameLocator('#emailbodyframe');
  await expect(htmlBodyFrame.locator('h1')).toHaveText('Launch Digest');
  await expect(htmlBodyFrame.locator('body')).toContainText('Migration window: Tuesday 09:00 UTC');
});

test('bulk mark read and unread updates row styling', async ({ page }) => {
  await waitForDemoInbox(page);

  const firstRow = page.locator('#messagelist a.emailheader').first();
  await expect(firstRow).not.toHaveClass(/read/);

  await firstRow.locator('.emailselection input[type="checkbox"]').check();
  await page.locator('#selected-email-actions button[name="email_action_mark_read"]').click();
  await expect(page).toHaveURL(/\/accounts\/1/);
  await expect(firstRow).toHaveClass(/read/);

  await firstRow.locator('.emailselection input[type="checkbox"]').check();
  await page.locator('#selected-email-actions button[name="email_action_mark_unread"]').click();
  await expect(page).toHaveURL(/\/accounts\/1/);
  await expect(firstRow).not.toHaveClass(/read/);
});

test('message list scroll position survives email action reload', async ({ page }) => {
  await waitForDemoInbox(page);
  await page.goto(await inboxPath(page));

  const messagelist = page.locator('#messagelist');
  const rows = page.locator('#messagelist a.emailheader');
  const rowCount = await rows.count();
  expect(rowCount).toBeGreaterThan(8);

  const targetRow = rows.last();
  await messagelist.evaluate((list) => {
    list.scrollTop = list.scrollHeight;
  });
  await expect
    .poll(async () => messagelist.evaluate((list) => list.scrollTop))
    .toBeGreaterThan(50);
  await expect(targetRow).toBeInViewport();

  const isRead = await targetRow.evaluate((row) => row.classList.contains('read'));
  const actionName = isRead ? 'email_action_mark_unread' : 'email_action_mark_read';
  const actionButton = targetRow.locator(`button[name="${actionName}"]`);
  await targetRow.hover();
  await actionButton.evaluate((button) => button.form.requestSubmit(button));
  await expect(page).toHaveURL(/\/accounts\/1\/folders\/\d+/);

  await expect
    .poll(async () => messagelist.evaluate((list) => list.scrollTop), {
      message: 'message list scroll position should be restored after reload',
      timeout: 5000
    })
    .toBeGreaterThan(50);
  await expect(targetRow).toBeInViewport();
});

test('toolbar actions apply to open message when no checkbox is selected', async ({ page }) => {
  await waitForDemoInbox(page);

  const firstRow = page.locator('#messagelist a.emailheader').first();
  const firstRowCheckbox = firstRow.locator('.emailselection input[type="checkbox"]');
  await expect(firstRow).not.toHaveClass(/read/);

  await firstRow.click();
  await expect(page.locator('#messagepreview')).toBeVisible();
  await expect(firstRowCheckbox).not.toBeChecked();
  await expect(page.locator('#selected-email-actions button[name="email_action_mark_read"]')).toBeEnabled();

  await page.locator('#selected-email-actions button[name="email_action_mark_read"]').click();
  await expect(page).toHaveURL(/selectedEmailId=/);
  await expect(firstRow).toHaveClass(/read/);
  await expect(firstRowCheckbox).not.toBeChecked();

  await expect(page.locator('#selected-email-actions button[name="email_action_mark_unread"]')).toBeEnabled();
  await page.locator('#selected-email-actions button[name="email_action_mark_unread"]').click();
  await expect(page).toHaveURL(/selectedEmailId=/);
  await expect(firstRow).not.toHaveClass(/read/);
});

test('per-email mark read and unread updates row styling', async ({ page }) => {
  await waitForDemoInbox(page);
  const inbox = await inboxPath(page);

  const firstRow = page.locator('#messagelist a.emailheader:not(.read)').first();
  await expect(firstRow).toHaveCount(1);
  const messageId = await firstRow.getAttribute('data-message-id');
  expect(messageId).toBeTruthy();

  await firstRow.hover();
  const markReadButton = firstRow.locator('.emailactions button[name="email_action_mark_read"]');
  await expect(markReadButton).toBeVisible();
  await markReadButton.evaluate((button) => button.form.requestSubmit(button));
  await page.waitForResponse((response) => response.url().includes('/emails/selection') && response.request().method() === 'POST');
  await page.goto(inbox);
  await expect(page.locator(`#email${messageId}`)).toHaveClass(/read/);

  const readRow = page.locator(`#email${messageId}`);
  await readRow.hover();
  const markUnreadButton = readRow.locator('.emailactions button[name="email_action_mark_unread"]');
  await expect(markUnreadButton).toBeVisible();
  await markUnreadButton.evaluate((button) => button.form.requestSubmit(button));
  await page.waitForResponse((response) => response.url().includes('/emails/selection') && response.request().method() === 'POST');
  await page.goto(inbox);
  await expect(readRow).not.toHaveClass(/read/);
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
  await expect(page.locator('#pagination-status')).toContainText(`${LAST_PAGE_COUNT} messages`);
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', new RegExp(`${DEMO_MESSAGE_COUNT} messages total`));
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', /Page 3 of 3/);
  await page.locator('#emailpagination a[title="Beginning"]').click();
  expect(await pageSubjects(page)).toEqual(descendingFirstPageSubjects);

  const descendingSubjects = await collectSubjectsAcrossPages(page, descendingUrl);
  expect(descendingSubjects).toHaveLength(DEMO_MESSAGE_COUNT);
  expect(new Set(descendingSubjects).size).toBe(DEMO_MESSAGE_COUNT);

  await page.goto(ascendingUrl);
  const ascendingFirstPageSubjects = await pageSubjects(page);
  expect(ascendingFirstPageSubjects).toHaveLength(PAGE_SIZE);
  expect(await pageGroupLabels(page)).toEqual(['Older', 'Last Three Months', 'This Month']);

  await page.locator('#emailpagination a[title="End"]').click();
  await expect(page.locator('#messagelist a.emailheader')).toHaveCount(LAST_PAGE_COUNT);
  await expect(page.locator('#pagination-status')).toContainText(`${LAST_PAGE_COUNT} messages`);
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', new RegExp(`${DEMO_MESSAGE_COUNT} messages total`));
  await expect(page.locator('#pagination-status')).toHaveAttribute('title', /Page 3 of 3/);
  expect(await pageGroupLabels(page)).toEqual(ASCENDING_END_GROUPS);
  await page.locator('#emailpagination a[title="Beginning"]').click();
  expect(await pageSubjects(page)).toEqual(ascendingFirstPageSubjects);

  const ascendingSubjects = await collectSubjectsAcrossPages(page, ascendingUrl);
  expect(ascendingSubjects).toHaveLength(DEMO_MESSAGE_COUNT);
  expect(new Set(ascendingSubjects).size).toBe(DEMO_MESSAGE_COUNT);
  expect(ascendingSubjects).toEqual([...descendingSubjects].reverse());
});
