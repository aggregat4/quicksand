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

test('keyboard shortcuts move focus and open preview', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.keyboard.press('j');
  const firstSubject = await focusedHeaderSubject(page);
  expect(firstSubject).toBeTruthy();

  await page.keyboard.press('j');
  const secondSubject = await focusedHeaderSubject(page);
  expect(secondSubject).toBeTruthy();
  expect(secondSubject).not.toEqual(firstSubject);

  await page.keyboard.press('k');
  expect(await focusedHeaderSubject(page)).toEqual(firstSubject);

  await page.keyboard.press('o');
  await expect(page.locator('#messagepreview')).toHaveAttribute('open', '');

  await page.keyboard.press('u');
  await expect(page.locator('#messagepreview')).not.toHaveAttribute('open', '');
});

test('keyboard shortcut slash focuses search', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.keyboard.press('/');
  await expect(page.locator('#searchemailinput')).toBeFocused();

  await page.keyboard.press('Escape');
  await expect(page.locator('#searchemailinput')).not.toBeFocused();
});

test('keyboard shortcut question mark shows help', async ({ page }) => {
  await waitForDemoInbox(page);

  await expect(page.locator('#keyboard-shortcuts-help')).toHaveCount(1);
  await page.locator('#messagelist').click();
  await page.keyboard.press('?');
  await expect(page.locator('#keyboard-shortcuts-help')).toHaveAttribute('open', '');
  await expect(page.locator('#keyboard-shortcuts-help-content dt')).not.toHaveCount(0);
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

async function focusedMessageId(page) {
  return await page.evaluate(() => {
    const header = document.activeElement;
    if (!(header instanceof HTMLAnchorElement) || !header.classList.contains('emailheader')) {
      return null;
    }
    return header.dataset.messageId ?? null;
  });
}

test('keyboard shortcut e archives consecutive messages from list focus', async ({ page }) => {
  await waitForDemoInbox(page);

  const totalBefore = await page.locator('#pagination-status').getAttribute('data-total-count');

  await page.keyboard.press('j');
  await expect(page.locator('#selected-email-actions button[name="email_action_archive"]')).toBeEnabled();
  const firstArchivedId = await focusedMessageId(page);
  expect(firstArchivedId).toBeTruthy();

  await page.keyboard.press('e');
  await waitForKeyboardShortcuts(page);
  await expect(page.locator(`#email${firstArchivedId}`)).toHaveCount(0);
  await expect(page.locator('#pagination-status')).toHaveAttribute(
    'data-total-count',
    String(Number(totalBefore) - 1)
  );

  await page.keyboard.press('j');
  await expect(page.locator('#selected-email-actions button[name="email_action_archive"]')).toBeEnabled();
  const secondArchivedId = await focusedMessageId(page);
  expect(secondArchivedId).toBeTruthy();
  expect(secondArchivedId).not.toEqual(firstArchivedId);

  await page.keyboard.press('e');
  await waitForKeyboardShortcuts(page);
  await expect(page.locator(`#email${secondArchivedId}`)).toHaveCount(0);
  await expect(page.locator('#pagination-status')).toHaveAttribute(
    'data-total-count',
    String(Number(totalBefore) - 2)
  );
});

test('archived message stays out of inbox after background sync', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.keyboard.press('j');
  const archivedId = await focusedMessageId(page);
  expect(archivedId).toBeTruthy();

  await page.keyboard.press('e');
  await waitForKeyboardShortcuts(page);
  await expect(page.locator(`#email${archivedId}`)).toHaveCount(0);

  await page.waitForTimeout(8_000);
  await page.reload();
  await waitForKeyboardShortcuts(page);
  await expect(page.locator(`#email${archivedId}`)).toHaveCount(0);

  await page.keyboard.press('g');
  await page.keyboard.press('a');
  await waitForKeyboardShortcuts(page);
  await expect(page.locator(`#email${archivedId}`)).toHaveCount(1);
});

test('keyboard shortcuts can be disabled via localStorage', async ({ page }) => {
  await waitForDemoInbox(page);

  await page.evaluate(() => {
    localStorage.setItem('quicksand.keyboardShortcutsEnabled', 'false');
  });
  await page.reload();
  await expect(page.locator('#messagelist a.emailheader')).not.toHaveCount(0);

  await page.keyboard.press('j');
  expect(await focusedHeaderSubject(page)).toBeNull();

  await page.evaluate(() => {
    localStorage.removeItem('quicksand.keyboardShortcutsEnabled');
  });
});
