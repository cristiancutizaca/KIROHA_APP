# Kiroha v0.1 — stabilization work-in-progress

> **Not a release candidate.** All changes below are committed on
> `fix/kiroha-v01-p0-reliability` and need compilation and hardware regression testing.

## Implemented in this branch

- Manga and anime downloads now have distinct persisted keys (`manga:<chapterId>` and
  `anime:<episodeId>`). Previously both types wrote numeric keys to the same
  `active_downloads` SharedPreferences, allowing cross-type overwrites.
- Legacy numeric records are migrated when their JSON representation matches the
  media type. Restoration no longer empties the entire preference store, and
  unresolved entries are retained rather than dropped.
- Queue insertions are persisted before UI notification; queue reordering uses
  one storage replacement instead of clear + re-add. Restored items are merged
  into the current queue to avoid duplicating new startup enqueues.
- Both source paging implementations can skip intermediate duplicate-only or
  empty pages; after twelve nonproductive pages they surface a retryable paging
  error instead of silently reporting the source as exhausted.
- Deduplication remains based on canonical URL; titles and author names do not
  define identity. Added a regression test for duplicate-only intermediary pages.
- Browse no longer starts permanent eager database subscriptions for every loaded
  card, and the initial paging load is bounded.
- Category assignment UI uses lazy rows and stable keys.
- Startup storage reconciliation is delayed and rate limited, and is skipped when
  manga or anime downloads are queued. Kiroha download recorders only perform
  expensive local file scans on completed items, not transient queue statuses.
- Exceptions in asynchronous startup indexing / recorder callbacks are logged
  without crashing the whole application (cancellation is still propagated).

## Build validation

GitHub Actions PR check for this branch:
https://github.com/cristiancutizaca/KIROHA_APP/actions/runs/38437168888

**Result:** GitHub marked the job failed before any step ran, with an empty steps
array and no job logs available. This did NOT compile or test these changes;
it cannot be interpreted as a source-code build failure.

Run in an Android/Gradle development environment:

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew testDebugUnitTest
./gradlew verifySqlDelightMigration
./gradlew assembleDebug
```

A successful Android build and installation are still required before merging.

## Hardware regression tests required

1. Queue 30+ manga downloads and 30+ anime downloads (including overlapping
   numeric local IDs); change tabs, kill process, and relaunch. Pending queue
   entries must survive, and completed media must remain available.
2. Enqueue, reorder, kill the process in the middle of activity, relaunch and
   check queue integrity. Test pause, resume and network loss.
3. Confirm chapter partial-page recovery on real SAF/internal storage.
4. Browse e-hentai or another very large source for 20+ pages; compare the same
   query/source to Tail. Verify no repeated pages, missing legitimate IDs, or
   multi-minute per-page processing. Repeat for Anime.
5. Add 100 items to a large library; reopen category dialog repeatedly and
   switch between Library and Browse. Collect timings.
6. Compare CPU/thermal behavior when downloading 5 concurrent chapters versus
   Tail with identical content and no restore/reindex running.

## Explicit remaining work

- Full crash-safe per-page manifest and granular persisted item states.
- Hardware proof of progress and sync across two or more devices.
- Full end-to-end reindex of existing Tail/Mihon downloads on external SAF/microSD.
- Download thermal-aware concurrency and broad work coordinator.
- Applying the **user-selected** original Kiroha icon asset to all launcher flavors
  (the exact image asset is not in this repository).
- Debug/universal APK packaging, hashing, signed release build.
- Large-library performance benchmarks and final real-device certification.

Do not tag or publish as v0.1 final until those checks are complete.
