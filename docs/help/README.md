# Navilife's built-in help

One file per feature. `python3 tools/gen-help.py` bundles them into core (`HelpIndex.kt`) so Ask and Talk answer
"how do I…" and "where is…" questions on the device, with **Take me there** (CI checks the bundle is up to date).

Front matter: `id` (the file name), `title`, `keywords` (comma-separated, whole words, unique across entries), `fold` and
`mac` (a `HelpPlace` from core's Help.kt, or `none`; `SYSTEM_…` are Android's own settings pages, Fold only),
`fold_where` and `mac_where` (the path a person follows; `mac: none` with no `mac_where` means Fold only). Then lines
`what:` (finishes "Title: …"), `how:`, optionally `mac_how:`, and any number of `tip:`. `data: yes` marks a feature
whose name is also his own data (News, Bins, Lists, Calendars…): "what's the news?" or the bare name stays a question
about the data, and only "how do I…"/"where is…" is answered from help.

Every row in Ask (the Fold's `MoreItem`) must have a `HelpPlace`, and every Navilife screen (all but Settings itself and
Android's own pages) an entry that opens it: the tool fails in CI otherwise.

The repository is public: no personal details in an entry (build plan rule 13).
