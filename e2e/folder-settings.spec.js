import { expect, test } from '@playwright/test';

async function openFolderSettings(page) {
  await page.goto('/accounts/1');
  await expect(page).toHaveURL(/\/accounts\/1\/settings\/folders$/);
  await expect(page.getByRole('heading', { name: 'Folder mappings' })).toBeVisible();
}

function roleGroup(page, label) {
  return page.getByRole('group', { name: new RegExp(`^${label}\\s`) });
}

test('folder setup create-and-map survives background sync and maps every role', async ({ page }) => {
  await openFolderSettings(page);

  const archiveGroup = roleGroup(page, 'Archive');
  await archiveGroup.getByRole('button', { name: 'Create and map' }).click();
  await expect(archiveGroup.locator('legend span')).toHaveText('Configured');

  // Allow at least one mail-fetcher cycle (playwright webServer uses 3s period).
  await page.waitForTimeout(4_000);
  await page.reload();

  const trashGroup = roleGroup(page, 'Trash');
  await expect(
    trashGroup.locator('select[name="folder_TRASH"] option', { hasText: 'Archive' })
  ).toHaveCount(0);
  await trashGroup.locator('input[name="create_name_TRASH"]').press('Enter');
  await expect(trashGroup.locator('legend span')).toHaveText('Configured');

  for (const role of ['Junk/Spam', 'Sent', 'Drafts']) {
    const group = roleGroup(page, role);
    await group.getByRole('button', { name: 'Create and map' }).click();
    await expect(group.locator('legend span')).toHaveText('Configured');
  }

  await page.getByRole('link', { name: 'Back to mailbox' }).click();
  await expect(page).toHaveURL(/\/accounts\/1(\/folders\/\d+)?$/);
});
