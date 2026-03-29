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
