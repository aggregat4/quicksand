# Prefix search and relevance sorting

## Goal

Make partially typed searches useful without turning every term into a broad wildcard query. Preserve
exact search as an explicit option and add relevance ordering as a search-only choice.

Search continues to run against the local SQLite `message_search` FTS5 table. It must not query IMAP
or introduce browser-owned search state.

## Query semantics

Parse the user's input into quoted phrases and unquoted Unicode letter/number tokens. Build the FTS5
expression in application code; never pass user-provided FTS syntax directly to `MATCH`.

- Combine all terms with `AND`, as today.
- Treat quoted terms or phrases as exact. A quoted phrase must match the same token sequence.
- Treat only the final syntactic term as a prefix when it is unquoted and its normalized token contains
  at least three characters.
- Treat an unquoted final term shorter than three characters as exact.
- Treat an unmatched quote as ordinary punctuation instead of rejecting the request or exposing FTS
  parser errors.
- Continue using the FTS table's `unicode61 remove_diacritics 2` tokenization behavior.

Examples:

| User query | Effective meaning |
|------------|-------------------|
| `launch dig` | exact `launch` and a token beginning with `dig` |
| `launch di` | exact `launch` and exact `di` |
| `launch "digest"` | exact `launch` and exact `digest` |
| `"launch digest"` | exact adjacent phrase `launch digest` |
| `"project alpha" upd` | exact phrase `project alpha` and a token beginning with `upd` |

Quoting is part of Quicksand's query language, not raw FTS5 passthrough. Literal punctuation inside
quoted text is interpreted through the same tokenizer as indexed mail.

## Highlighting

Subject, actor, excerpt, and message-body highlighting must use the parsed query rather than a separate
substring interpretation:

- exact terms highlight complete matching tokens;
- prefix terms highlight the matching prefix at the start of a token; and
- exact phrases may highlight their constituent tokens.

Text that merely contains the query in the middle of a token must not be highlighted as a prefix match.

## Ordering

Keep chronological ordering as the default. Add a search-only order selector with:

- `Newest` — received date descending;
- `Oldest` — received date ascending; and
- `Best match` — FTS5 relevance, with newest messages breaking equal scores.

Represent this with a search-specific order type or request parameter. Do not add relevance to the
general mailbox `SortOrder`, because ordinary folder pages do not have an FTS rank.

For `Best match`, calculate `bm25(message_search, ...)` and order by:

1. rank ascending (FTS5 returns better matches as lower values);
2. `received_date_epoch_s` descending; and
3. message ID descending.

Weight subject and actors above body content. Initial suggested relative weights are subject `8`, actors
`5`, body excerpt `2`, and body `1`; verify these with representative searches before treating the values
as final. The selected order must survive search pagination links and opening or closing the viewer.

Relevance pagination needs rank plus received date and message ID as its stable cursor. The existing
date-only cursor remains unchanged for `Newest` and `Oldest`. A mailbox sync may change ranks between
requests; pagination must remain deterministic for an unchanged index and must not duplicate rows within
one response.

## Performance

FTS5 supports prefix matching without a schema change, using a token-range scan. The three-character
minimum and final-term-only rule bound the initial cost.

Do not add FTS prefix indexes initially. Benchmark representative small and large mirrored mailboxes,
including result counting and page loading. Add configured prefix lengths in a later migration only if
measurements show a material regression; prefix indexes trade database size and sync-time indexing work
for faster prefix queries.

## Verification

Add automated coverage for:

- a three-or-more-character final prefix matching a longer token;
- a one- or two-character final term remaining exact;
- preceding unquoted terms remaining exact;
- quoted exact terms and multi-token phrases;
- mixed quoted and prefix terms;
- unmatched quotes, punctuation, Unicode, case, and diacritics;
- no cross-account results;
- matching and non-matching highlights at token boundaries;
- deterministic newest, oldest, and best-match ordering;
- relevance pagination where several messages have equal scores; and
- preservation of query and ordering parameters in pagination and viewer URLs.

The browser-level acceptance case should demonstrate that `Launch Dig` finds a message containing
`Launch Digest`, while `Launch "Dig"` does not match that message.
