# Recovered dependencies

The original `bit.himitsu` package was a private Git submodule
(`gitlab.com/RepoDevil/himitsu-submodule`, pinned at `app/src/main/java/bit`)
that no longer exists, and the TorrServer AAR was downloaded at build time
from a repository that has also been deleted. Both were recovered so the
project can build again.

## `app/libs/bit-himitsu.jar` (implementation)

`bit/himitsu/**` classes extracted from the official **Himitsu 1.0.0-matagi**
APK via dex2jar. v1.0.0 was chosen over v1.0.2 because its Kotlin metadata
(`mv=[1,9,0]`) is compatible with this project's Kotlin 1.9.23 compiler
(v1.0.2 classes carry `mv=[2,0,0]`).

A `META-INF/himitsu.kotlin_module` mapping file was regenerated and injected
— dex conversion loses it, and without it Kotlin cannot resolve the
package-level (file-facade) functions this codebase imports
(`bit.himitsu.textView`, `bit.himitsu.nio.utf8`, `bit.himitsu.torrServerStart`,
`bit.himitsu.forceShowIcons`, `bit.himitsu.onCompletedAction`, ...).

Classes provided by source instead (removed from / never present in the jar):

| Class | Source file | Why |
|---|---|---|
| `bit.himitsu.os.Version` | `bit/himitsu/os/Version.kt` | jar era used `@JvmStatic`; this codebase uses property access (virtual). `@JvmStatic val` supplies both ABIs. |
| `bit.himitsu.search.ReverseSearchDialogFragment` | `bit/himitsu/search/ReverseSearchDialogFragment.kt` | jar class extends a same-module source class — unresolvable; ported to Kotlin. |
| `bit.himitsu.net.Bandwidth` | `bit/himitsu/net/Bandwidth.kt` | absent from the 1.0.0 jar. |
| `bit.himitsu.viewpager.FidgetSpinTransformer` | `bit/himitsu/viewpager/FidgetSpinTransformer.kt` | absent from the 1.0.0 jar. |
| `openStreamTab` / `openPluginTab` | `bit/himitsu/webkit/ChromeTabs.kt` | added to `ChromeIntegration` after 1.0.0; reimplemented as extension functions. |
| `bit.himitsu.io.Debug` | `bit/himitsu/io/Debug.kt` | ported to source; the jar must NOT re-ship it (duplicate-class dex failure). |

### Binary patch applied to the jar

`bit/himitsu/update/MatagiUpdater.class` bakes `RepoDevil/Himitsu` (the old,
dead repo) into its `getRepo()` constant. The UTF-8 constant-pool entry was
rewritten to `Nekomo-App/HimitsuX`, so the Matagi updater now polls
`api.github.com/repos/Nekomo-App/HimitsuX/releases` and matches the
commit-hash tags / `Himitsu-<tag>-google-<abi>-matagi.apk` assets this repo
publishes. No other jar entry references the old repo; `bit.himitsu.io.Debug`
(source) builds the issue-report URL and was already updated.

## `app/stubs/app-stubs.jar` (compileOnly, never packaged)

Stale 1.0.0-era compiled `ani.himitsu.**`/`eu.kanade.**` app classes. The
binary Kotlin signatures inside `bit-himitsu.jar` reference app classes that
only exist as sources in this module; Kotlin cannot resolve binary→source
references, so the old binaries are supplied at compile time for signature
resolution. `compileOnly` means they are never dexed/packaged — the real
classes come from source. Do **not** copy these into `libs/`.

## TorrServer

`com.github.recloudstream:torrentserver:7861970` (JitPack) replaces the dead
`RepoDevil/TorrServer` download — same `torrServer.TorrServer` gomobile API.
`TorrentServerService` was adjusted to the newer `startTorrentServer(path,
port)` signature that returns the bound port.

## Other dead dependencies replaced

- `com.github.AAChartModel:AAChartCore-Kotlin` `7.2.1` → `7.2.3` (7.2.1 never built on JitPack)
- `com.github.inorichi.injekt:injekt-core` `65b0440` → `d649d37` (last good JitPack build)
- KSP `1.9.10-1.0.13` → `1.9.23-1.0.20` (must match Kotlin 1.9.23)

## Known version skew

The jar is compiled from v1.0.0 while this source tree is a later commit.
Classes that were renamed/changed between the two are handled in source
(e.g. `AnilistSearch` vs `AniListSearch`). If a runtime linkage error ever
surfaces from a jar class, the fix is the same pattern as `Version`: port the
class to Kotlin source and delete it from the jar.
