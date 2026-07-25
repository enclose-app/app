---
name: room-migration
description: Use when changing anything Room persists in this app — adding or removing an entity, column, table, or index, or bumping the database version. Covers the mandatory no-data-loss migration procedure, generating the exported schema, writing the Migration against it, and the upsert gotcha that silently wipes new columns.
---

# Changing the Room schema

## The policy this exists to enforce

`EncloseDatabase` has **no `fallbackToDestructiveMigration`, and none may be
added.** A territory is a walk someone went out and did; it cannot be re-entered
from the couch. Dropping tables to land a schema change is never an acceptable
trade here, pre-release included.

The consequence: **a version bump with no matching `Migration` throws
`IllegalStateException` when the database is opened.** That is deliberate — a
loud crash in development instead of silently emptied tables in the field. It
also means you cannot leave a schema change half-done.

## Procedure

1. **Edit the entity** in `app/src/main/java/io/app/enclose/data/`. New columns
   should be nullable or carry a default, so existing rows remain valid.

2. **Carry the field through the domain mapping.** For `TerritoryEntity` that
   means all three of: the `Territory` domain class, `toDomain()`, and
   `fromDomain()`. See the gotcha below — skipping one wipes data at runtime
   without any compile error.

3. **Bump `version`** in the `@Database` annotation in `EncloseDatabase.kt`.

4. **Generate the exported schema:**
   ```bash
   ./gradlew compileDebugKotlin
   ```
   KSP writes `app/schemas/io.app.enclose.data.EncloseDatabase/<N>.json`.
   This is why `exportSchema = true` and the `room.schemaLocation` KSP arg exist.

5. **Write the `Migration`** as a `private val` in `EncloseDatabase.companion`.
   Three worked examples are already there:
   - `MIGRATION_5_6` — add a `NOT NULL` column with a default
   - `MIGRATION_6_7` — add nullable columns
   - `MIGRATION_7_8` — create tables

   For **new tables, copy the SQL verbatim from the generated JSON.** Room
   validates the live schema against that file and compares strings, so
   hand-written `CREATE TABLE` will fail on a stray backtick or space:
   ```bash
   python3 -c "
   import json, sys
   v = sys.argv[1]
   d = json.load(open('app/schemas/io.app.enclose.data.EncloseDatabase/%s.json' % v))
   for e in d['database']['entities']:
       print(e['createSql'].replace('\${TABLE_NAME}', e['tableName']))
       print()
   " 8
   ```

6. **Register it** in `.addMigrations(...)` in `get()`. Easy to forget; the
   failure only appears at runtime on a device that has the old database.

7. **Verify** — see the `verify` skill. Then confirm on a device that already
   holds the *previous* version, because a fresh install creates the new schema
   directly and never exercises the migration at all:
   ```bash
   ./gradlew installDebug            # over an existing install, do NOT uninstall
   adb shell am start -n io.app.enclose/.MainActivity
   adb logcat -d | grep -iE "sqlite|room|IllegalStateException" | tail -20
   ```
   The `device-check` skill has commands for reading the on-device tables.

8. **Commit the generated `<N>.json` with the code.** Without it the next
   migration has no real previous schema to be written against, and the whole
   policy degrades into guesswork.

## Gotchas

- **`upsert` is `@Insert(OnConflictStrategy.REPLACE)` — a whole-row write.**
  Anything missing from the domain object is replaced with its default, not left
  alone. `TerritoryRepository.claim()` goes through this path, so a new column
  that isn't carried through `toDomain()`/`fromDomain()` is silently reset on the
  next rename, recolor, or notes edit. `city` survives only because every caller
  copies a `Territory` that was read from the database.
- Prefer a **targeted `@Query` update** over `upsert` for a single derived field
  (`updateCity` is the pattern), and consider whether it should touch
  `syncStatus`: locally derived data shouldn't queue an upload, user edits should.
- **Multi-row writes that only make sense together belong in one
  `@Transaction`** (`TerritoryDao.applyClaim` is the example). `TerritoryDao` and
  `WalkProgressDao` are abstract classes rather than interfaces precisely so they
  can host these.
- Versions **1–4 have no exported schema** (`exportSchema` was off then) and no
  migrations. An install still on one of those cannot be upgraded and must be
  reinstalled. Everything from v5 forward is covered.
