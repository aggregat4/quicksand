import { expect, test } from '@playwright/test';

async function waitForKeyboardShortcuts(page) {
  await page.waitForSelector('body[data-keyboard-shortcuts="ready"]');
}

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
  await waitForKeyboardShortcuts(page);
}

async function focusedHeaderSubject(page) {
  return await page.evaluate(() => {
    const header = document.activeElement;
    if (!(header instanceof HTMLAnchorElement) || !header.classList.contains('emailheader')) {
      return null;
    }
    return header.querySelector('.subjectline')?.textContent?.trim() ?? null;
  });
}

async function focusedMessageId(page) {
  return await page.evaluate(() => {
    const header = document.activeElement;
    if (!(header instanceof HTMLAnchorElement) || !header.classList.contains('emailheader')) {
      return null;
    }
    return header.dataset.messageId ?? null;
  });
}

async function closeComposer(page) {
  await page.frameLocator('#newmail-composer-frame').getByRole('button', { name: 'Close composer' }).click();
  await expect(page.locator('#newmail-composer-dialog')).not.toHaveAttribute('open', '');
}

async function dismissComposerWithEscape(page) {
  await page.locator('button[name="create_new_email"]').focus();
  await page.keyboard.press('Escape');
  await expect(page.locator('#newmail-composer-dialog')).not.toHaveAttribute('open', '');
}

test('keyboard shortcuts move focus and open preview', async ({ page }) => {
  await waitForDemoInbox(page);

  await expect(page.locator('#messagelist a.emailheader[tabindex="0"]')).toHaveCount(1);
  const firstMessageId = await page.locator('#messagelist a.emailheader').first().getAttribute('data-message-id');

  await page.keyboard.press('j');
  const firstSubject = await focusedHeaderSubject(page);
  expect(firstSubject).toBeTruthy();
  expect(await focusedMessageId(page)).toEqual(firstMessageId);

  await page.keyboard.press('j');
  const secondSubject = await focusedHeaderSubject(page);
  expect(secondSubject).toBeTruthy();
  expect(secondSubject).not.toEqual(firstSubject);

  await page.keyboard.press('k');
  expect(await focusedHeaderSubject(page)).toEqual(firstSubject);

  await page.keyboard.press('Enter');
  await expect(page.locator('#messagepreview')).toHaveAttribute('open', '');

  await page.keyboard.press('Escape');
  await expect(page.locator('#messagepreview')).not.toHaveAttribute('open', '');

  await page.keyboard.press('o');
  await expect(page.locator('#messagepreview')).toHaveAttribute('open', '');

  await page.keyboard.press('u');
  await expect(page.locator('#messagepreview')).not.toHaveAttribute('open', '');
});

test('keyboard shortcut slash focuses search', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.keyboard.press('/');
  await expect(page.locator('#searchemailinput')).toBeFocused();

  await page.keyboard.press('j');
  await expect(page.locator('#searchemailinput')).toHaveValue('j');

  await page.keyboard.press('Escape');
  await expect(page.locator('#searchemailinput')).not.toBeFocused();
});

test('keyboard shortcut question mark shows help', async ({ page }) => {
  await waitForDemoInbox(page);

  await expect(page.locator('#keyboard-shortcuts-help')).toHaveCount(1);
  await page.locator('#messagelist').click();
  await page.keyboard.press('?');
  await expect(page.locator('#keyboard-shortcuts-help')).toHaveAttribute('open', '');
  await expect(page.locator('#keyboard-shortcuts-help-content dt')).toHaveCount(23);

  const urlBeforeBlockedAction = page.url();
  await page.keyboard.press('e');
  expect(page.url()).toEqual(urlBeforeBlockedAction);

  await page.keyboard.press('Escape');
  await expect(page.locator('#keyboard-shortcuts-help')).not.toHaveAttribute('open', '');
});

test('keyboard shortcut x toggles row selection', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.keyboard.press('j');
  await page.keyboard.press('x');

  const checked = await page.locator('#messagelist .emailselection input[type=checkbox]:checked').count();
  expect(checked).toBe(1);

  await page.keyboard.press('x');
  expect(await page.locator('#messagelist .emailselection input[type=checkbox]:checked').count()).toBe(0);
});

test('message action shortcuts submit the focused message through the existing form', async ({ page }) => {
  await page.route('**/emails/selection', route => route.fulfill({ status: 204 }));
  const actions = [
    ['e', 'email_action_archive'],
    ['#', 'email_action_delete'],
    ['!', 'email_action_mark_spam'],
    ['Shift+i', 'email_action_mark_read'],
    ['Shift+u', 'email_action_mark_unread']
  ];

  for (const [key, actionName] of actions) {
    await waitForDemoInbox(page);
    await page.keyboard.press('j');
    const messageId = await focusedMessageId(page);
    const requestPromise = page.waitForRequest(request =>
      request.method() === 'POST' && new URL(request.url()).pathname === '/emails/selection'
    );

    await page.keyboard.press(key);
    const request = await requestPromise;
    const params = new URLSearchParams(request.postData());
    expect(params.get('email_select')).toEqual(messageId);
    expect(params.has(actionName)).toBe(true);
  }
});

test('move shortcut opens the dialog and suppresses background shortcuts', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.keyboard.press('j');
  const messageId = await focusedMessageId(page);
  const urlBeforeDialog = page.url();
  await page.keyboard.press('v');
  await expect(page.locator('#move-emails-dialog')).toHaveAttribute('open', '');

  await page.keyboard.press('e');
  expect(page.url()).toEqual(urlBeforeDialog);
  await expect(page.locator(`#email${messageId}`)).toHaveCount(1);

  await page.keyboard.press('Escape');
  await expect(page.locator('#move-emails-dialog')).not.toHaveAttribute('open', '');
});

test('compose, reply, and forward shortcuts open the composer with the expected draft', async ({ page }) => {
  await waitForDemoInbox(page);
  const composerRequests = [];
  await page.route('**/accounts/1/emails?**', async route => {
    composerRequests.push(new URL(route.request().url()));
    await route.fulfill({ status: 200, body: 'about:blank' });
  });

  await page.keyboard.press('c');
  await expect(page.locator('#newmail-composer-dialog')).toHaveAttribute('open', '');
  await expect.poll(() => composerRequests.length).toBe(1);
  expect(composerRequests[0].searchParams.has('replyEmail')).toBe(false);
  expect(composerRequests[0].searchParams.has('forwardEmail')).toBe(false);
  const urlBeforeBlockedChord = page.url();
  await page.keyboard.press('g');
  await page.keyboard.press('a');
  expect(page.url()).toEqual(urlBeforeBlockedChord);
  await dismissComposerWithEscape(page);

  await page.keyboard.press('j');
  const replyMessageId = await focusedMessageId(page);
  await page.keyboard.press('r');
  await expect(page.locator('#newmail-composer-dialog')).toHaveAttribute('open', '');
  await expect.poll(() => composerRequests.length).toBe(2);
  expect(composerRequests[1].searchParams.get('replyEmail')).toEqual(replyMessageId);
  await dismissComposerWithEscape(page);

  await page.keyboard.press('j');
  const forwardMessageId = await focusedMessageId(page);
  await page.keyboard.press('f');
  await expect(page.locator('#newmail-composer-dialog')).toHaveAttribute('open', '');
  await expect.poll(() => composerRequests.length).toBe(3);
  expect(composerRequests[2].searchParams.get('forwardEmail')).toEqual(forwardMessageId);
  await dismissComposerWithEscape(page);
});

test('opening a focused draft uses the composer instead of message preview', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.keyboard.press('j');
  await page.keyboard.press('r');
  await expect(page.frameLocator('#newmail-composer-frame').locator('#email-subject')).toHaveValue(/^Re: /);
  await closeComposer(page);

  await page.keyboard.press('g');
  await page.keyboard.press('d');
  await waitForKeyboardShortcuts(page);
  await expect(page.locator('#messagelist a.emailheader')).not.toHaveCount(0);

  await page.keyboard.press('j');
  await page.keyboard.press('o');
  await expect(page.locator('#newmail-composer-dialog')).toHaveAttribute('open', '');
  await expect(page.locator('#messagepreview')).toHaveCount(0);
});

test('all folder chords navigate through the rendered sidebar links', async ({ page }) => {
  const chords = [
    ['i', 'inbox'],
    ['t', 'sent'],
    ['d', 'drafts'],
    ['a', 'archive'],
    ['b', 'junk']
  ];

  for (const [key, specialUse] of chords) {
    await waitForDemoInbox(page);
    const href = await page.locator(`#folderlist a[data-folder-special-use="${specialUse}"]`).getAttribute('href');
    await page.route(`**${href}`, route => route.fulfill({ status: 204 }));
    const requestPromise = page.waitForRequest(request =>
      new URL(request.url()).pathname === new URL(href, page.url()).pathname
    );
    await page.keyboard.press('g');
    await page.keyboard.press(key);
    const request = await requestPromise;
    expect(new URL(request.url()).pathname).toEqual(new URL(href, page.url()).pathname);
    await page.unroute(`**${href}`);
  }
});

test('an expired chord prefix does not trigger navigation', async ({ page }) => {
  await waitForDemoInbox(page);

  const urlBeforeChord = page.url();
  await page.keyboard.press('g');
  await page.waitForTimeout(1_100);
  await page.keyboard.press('a');
  expect(page.url()).toEqual(urlBeforeChord);
});

test('shortcuts work on server-rendered search results', async ({ page }) => {
  await waitForDemoInbox(page);

  const subject = await page.locator('#messagelist .subjectline').first().textContent();
  const query = subject.trim().split(/\s+/)[0];
  await page.goto(`/accounts/1/search?query=${encodeURIComponent(query)}`);
  await waitForKeyboardShortcuts(page);
  await expect(page.locator('#messagelist a.emailheader')).not.toHaveCount(0);

  await page.keyboard.press('j');
  expect(await focusedMessageId(page)).toBeTruthy();
  await page.keyboard.press('o');
  await expect(page.locator('#messagepreview')).toHaveAttribute('open', '');
});
