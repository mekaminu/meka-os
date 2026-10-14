# Navilife's built-in help

One file per feature. `python3 tools/gen-help.py` bundles them into core (`HelpIndex.kt`) so Ask and Talk answer
"how do I…" and "where is…" questions on the device, with **Take me there** (CI checks the bundle is up to date).

Front matter: `id` (the file name), `title`, `keywords` (comma-separated, whole words, unique across entries), `fold` and
`mac` (a `HelpPlace` from core's Help.kt, or `none`; `SYSTEM_…` are Android's own settings pages, Fold only),
`fold_where` and `mac_where` (the path a person follows; `mac: none` with no `mac_where` means Fold only). Then lines
`what:` (finishes "Title: …"), `how:`, optionally `mac_how:`, and any number of `tip:`.

The repository is public: no personal details in an entry (build plan rule 13).
