# M2 v2 hotfix

Replace only:

`tools/canonicalize_v407.py`

with the file in this pack, commit it to `main`, then rerun:

**Actions → Canonicalize Crypto TradeStation v4.0.7 → Run workflow**

## What changed

The v1 canonicalizer could generate Python bytecode under `.cts-v4-migration`
during Actions (Python 3.12). The repository does not ignore `__pycache__` or
`*.pyc`, and `git restore` does not remove new untracked files.

v2:

- validates Python syntax in memory instead of `python -m py_compile`;
- launches every migration script with `python -B`;
- sets `PYTHONDONTWRITEBYTECODE=1`;
- restores tracked migration files after materialization;
- removes only newly generated untracked files below `.cts-v4-migration`;
- performs a strict clean check before proceeding.

No trading logic is changed.
