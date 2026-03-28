# TODO

This file captures the next sensible work from the current `main` branch state.

Current baseline:

- Java 25 JVM-only app
- Helidon 4 migration completed
- build and tests are green with `mvn clean test`
- default runtime is now plain and safe by default
- demo mail server/account are opt-in via `-Ddemo.enabled=true`

## 1. Real Persisted Message Viewer

Status:

- The account and folder pages are backed by SQLite-backed message lists.
- The message viewer path is still mock-driven in [`src/main/java/net/aggregat4/quicksand/webservice/EmailWebService.java`](/home/boris/dev/projects/quicksand/src/main/java/net/aggregat4/quicksand/webservice/EmailWebService.java).
- `emailHandler()` and `htmlEmailBodyHandler()` still use `MockEmailData` rather than persisted message data.

Goal:

- Make `/emails/{emailId}/viewer` and `/emails/{emailId}/viewer/body` render actual locally stored messages.

Likely work:

- Extend [`src/main/java/net/aggregat4/quicksand/service/EmailService.java`](/home/boris/dev/projects/quicksand/src/main/java/net/aggregat4/quicksand/service/EmailService.java) with single-message lookup methods.
- Add repository support for fetching a message by local email id and, if needed, by message UID.
- Decide how stored message body data should work:
  - plaintext only as a first step, or
  - plaintext plus HTML body storage/sanitization.
- Replace `MockEmailData` lookups in `EmailWebService` with repository-backed data.
- Define behavior for missing messages: `404`, not mock fallback.

Open design question:

- The current database model does not obviously store full persisted viewer-ready bodies end-to-end yet. We may need a small schema extension for:
  - plaintext body
  - HTML body
  - excerpt/snippet
  - body content type

Acceptance criteria:

- Selecting a real email from a folder renders that email in the viewer.
- The body iframe endpoint returns the real stored body.
- Missing email ids return a proper error instead of mock content.
- Add tests for repository/service/web route behavior.

## 2. Real Draft Persistence And Composer Flow

Status:

- Composer creation is stubbed in [`src/main/java/net/aggregat4/quicksand/webservice/AccountWebService.java`](/home/boris/dev/projects/quicksand/src/main/java/net/aggregat4/quicksand/webservice/AccountWebService.java).
- New/reply/forward draft ids are fake (`42`, `100`, `200`).
- Composer rendering in [`src/main/java/net/aggregat4/quicksand/webservice/EmailWebService.java`](/home/boris/dev/projects/quicksand/src/main/java/net/aggregat4/quicksand/webservice/EmailWebService.java) is still based on `MockEmailData`.
- Sending/deleting only validates and redirects.

Goal:

- Make drafts first-class persisted entities and make the composer operate on real data.

Likely work:

- Decide whether drafts live in the same `messages` table or in a dedicated drafts table.
- Add repository methods for:
  - create draft
  - load draft
  - update draft fields
  - delete draft
  - mark queued-for-send
- Replace fake draft id generation in `AccountWebService`.
- Make reply/forward creation derive recipients/subject/body from a real source email.
- Update composer POST handling to persist form fields instead of just validating in memory.
- Introduce a minimal send queue model instead of immediate “success” redirect.

Suggested first slice:

- Persist plain drafts without attachments first.
- Keep queued send as a simple local state transition.
- Add attachments only after the draft flow is stable.

Acceptance criteria:

- Creating a new message creates a real draft.
- Reopening a composer shows the stored draft state.
- Reply/forward produce a real derived draft.
- Send/delete mutate persisted state, not just logs/redirects.


## 4. E2E Testing With Playwright

Status:

- The project has unit/integration coverage through Maven tests.
- There is no browser-level regression suite for the MPA flows, dialogs, iframes, history behavior, and static assets.

Goal:

- Add Playwright-based end-to-end coverage for the core HTML-first interaction model.

Why this should be near the beginning:

- The main product risk now is UI and route behavior across real browser navigation, not just backend correctness.
- Upcoming work on the real viewer/composer flow will be safer if we already have browser coverage for page rendering and core navigation.
- This project’s design depends on classic browser primitives; those are exactly what E2E tests should protect.

Suggested first scope:

- home page renders
- account page renders
- folder navigation works
- message selection updates the viewer flow correctly
- composer dialog/iframe flow opens
- static assets load

Suggested second scope after real data-backed viewer/composer work:

- viewing a real persisted email
- creating a draft
- replying/forwarding
- validation error rendering
- queued send flow

Implementation notes:

- Use Playwright against the running JVM app, not mocked browser-only pages.
- Prefer deterministic seeded test data or demo-mode fixtures for early tests.
- Keep the E2E suite focused on route/document behavior and the dialog/iframe model.
- Avoid turning the suite into a screenshot-only visual test harness.

Acceptance criteria:

- `npm`/Playwright setup is checked into the repo.
- There is a documented command to run E2E tests locally.
- At least one happy-path smoke suite covers the main MPA browser flows.

## 5. Frontend Platform Modernization Pass

Status:

- The frontend was built before several useful browser features were broadly practical to rely on.
- The current UI uses a number of custom behaviors around dialogs, layout coordination, and stateful styling that may now be simpler.

Goal:

- Revisit the frontend with current browser capabilities in mind and simplify wherever platform features can replace custom code.

Important constraint:

- This is not a SPA rewrite.
- The aim is to simplify and strengthen the existing SSR/MPA model by using better browser primitives.

Features worth evaluating:

- `:has()` selectors for parent/state styling and reducing JS class toggling
- `popover` for lightweight overlays where full dialog semantics are not needed
- improved `dialog` support now that the platform behavior is more mature
- container queries for account/message pane layout and responsive sub-layouts
- modern form and focus behavior that may remove custom event glue

Places to inspect first:

- [`src/main/resources/static/js/main.js`](/home/boris/dev/projects/quicksand/src/main/resources/static/js/main.js)
- [`src/main/resources/static/js/messageviewer.js`](/home/boris/dev/projects/quicksand/src/main/resources/static/js/messageviewer.js)
- [`src/main/resources/templates/account.peb`](/home/boris/dev/projects/quicksand/src/main/resources/templates/account.peb)
- [`src/main/resources/templates/emailviewer.peb`](/home/boris/dev/projects/quicksand/src/main/resources/templates/emailviewer.peb)
- [`src/main/resources/templates/emailcomposer.peb`](/home/boris/dev/projects/quicksand/src/main/resources/templates/emailcomposer.peb)
- [`src/main/resources/static/css/account.css`](/home/boris/dev/projects/quicksand/src/main/resources/static/css/account.css)
- [`src/main/resources/static/css/base.css`](/home/boris/dev/projects/quicksand/src/main/resources/static/css/base.css)

Specific opportunities:

- replace some JS-driven parent/selection styling with `:has()`
- re-evaluate whether some dialog wrappers can become simpler with current `dialog` behavior
- check whether popovers are a better fit for lighter transient UI than full dialogs
- use container queries to make split-pane and account-page layout depend on component width instead of global viewport assumptions
- reduce layout code that exists mainly to work around earlier browser limitations

Suggested output of this pass:

- a concrete list of simplifications to implement
- a smaller JS surface for UI orchestration
- a clearer separation between:
  - real product behavior that needs JS
  - legacy workaround code that the platform can now replace

Acceptance criteria:

- Documented decisions on which modern platform features are now allowed in this repo.
- At least one real simplification lands in CSS/HTML/JS using current browser capabilities.
- Container-query-based layout improvements are evaluated for the account/message UI.

## 6. Home Page And Navigation Cleanup

Status:

- [`src/main/java/net/aggregat4/quicksand/webservice/HomeWebService.java`](/home/boris/dev/projects/quicksand/src/main/java/net/aggregat4/quicksand/webservice/HomeWebService.java) still renders placeholder-like content.
- Current output still includes `Hello World!`.

Goal:

- Make `/` represent the real product state.

Possible directions:

- Redirect straight to the first account when accounts exist.
- Show an account chooser / recent inbox view.
- Show onboarding if no accounts exist.

Recommendation:

- Keep `/` as a real HTML page, but make it stateful:
  - zero accounts: setup page / explanation
  - one account: optional redirect to that account
  - multiple accounts: account chooser

Acceptance criteria:

- `/` no longer feels like starter scaffolding.
- Behavior is intentional for zero, one, and multiple account states.

## 7. Message Body Storage Model

This is a dependency for items 1 and 2.

Current risk:

- The sync path in [`src/main/java/net/aggregat4/quicksand/jobs/ImapStoreSync.java`](/home/boris/dev/projects/quicksand/src/main/java/net/aggregat4/quicksand/jobs/ImapStoreSync.java) still stores headers/metadata more than complete viewer-ready bodies.

Need to decide:

- What exact body formats are stored locally?
- Do we store:
  - plaintext only
  - HTML only
  - both
  - normalized snippet/excerpt
- How do we sanitize and cache HTML bodies?

Recommended first step:

- Store plaintext and HTML separately when available.
- Keep sanitization on read/render, not on raw storage.
- Add an excerpt/snippet column later if useful for list rendering.

## 8. Attachment Persistence

Status:

- Attachment routes exist, but attachment content is still mock-backed.
- Composer upload handling currently only collects filenames in memory.

Goal:

- Move attachment handling to a real persisted/local-file-backed model.

Likely work:

- Introduce local attachment storage on disk plus metadata in SQLite.
- Persist uploaded draft attachments.
- Make the attachment endpoint serve real files tied to real messages/drafts.
- Decide lifecycle rules for temporary draft attachments vs sent/received attachments.

Recommendation:

- Do this after real draft persistence is working.

## 9. Runtime Hardening

Recent improvements:

- demo services are opt-in
- default runtime no longer assumes local GreenMail ports
- static assets now use `StaticContentFeature`

Still worth doing:

- Add explicit runtime profiles or documented property sets:
  - plain local runtime
  - demo runtime
  - test runtime
- Make logging configuration less noisy/legacy if needed.
- Consider making server host default to `127.0.0.1` for local development and override in Docker/deploy.
- Review whether `application.conf` should keep any demo account shape at all once onboarding exists.

## 10. Suggested Order

Recommended implementation order for the next sessions:

1. E2E testing with Playwright smoke coverage
2. Frontend platform modernization pass
3. Message body storage model
4. Real persisted message viewer
5. Real draft persistence
6. Reply/forward based on real messages
7. Send queue model
8. Home page/onboarding
9. Account setup UI
10. Attachment persistence

## 11. Useful Commands

Verification:

```bash
mvn clean test
mvn -DskipTests package
java -Dserver.port=0 -jar target/quicksand.jar
java -Ddemo.enabled=true -Dmail_fetcher.enabled=true -jar target/quicksand.jar
```

## 12. Good First Next Task

If picking a single next task next time, start here:

- implement repository/service support for loading one real email by id
- wire `/emails/{emailId}/viewer` to that real email
- return `404` for unknown ids
- leave composer/drafts for the follow-up pass

That gives the fastest product improvement with the least architectural churn.
