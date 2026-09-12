# Build validation — KSP Ani Clean v1.5.12

Validated in the available Linux/JDK 21 environment on 2026-08-18.

## Passed

- Core clean-room self-test: PASS, including wrong-series and provider-native search-result regressions.
- `ProviderPageIdentity`, `TitleMatcher`, and their domain dependencies compiled with JDK 21.
- Changed GogoAnime source pipeline compiled with JDK 21 using a local `JsonHttpClient` dependency stub.
- Lazy GogoAnime smoke test: PASS. The resolver now emits provider search URLs, not guessed episode URLs.
- Search-result identity regression: wrong `Grand Blue Season 3` result rejected; matching `Mushoku Tensei III` result accepted.
- Explicit Season 3 fail-closed regression: an unnumbered base-series result is rejected for the Season 3 media entry.
- MainWindow Java parser scan found no syntax-level errors; unresolved JavaFX/application symbols are expected because this Linux environment does not contain the Windows JavaFX runtime dependencies.
- Existing embedded-player fit-to-window, scrollbar suppression, popup blocking, and autoplay pipeline retained.

## Runtime behavior corrected

The v1.5.4 fallback could derive multiple episode URLs from AniList title slugs. Unknown provider slugs may redirect to unrelated series, so every candidate could correctly fail the wrong-series gate.

v1.5.5 removes that guessed-URL stage. The hidden WebView now follows:

`provider search -> verified real series result -> exact provider episode link -> identity check -> isolated player`

The provider webpage stays hidden throughout discovery.

## Not executed here

A native Windows JavaFX/WebView launch cannot be authoritatively reproduced in this Linux container. Run `RUN.bat` on the Windows target. That remains the final acceptance step for live provider search results, provider-side scripts, codecs, WebView sizing, and autoplay behavior.


## v1.5.6 validation performed in this environment

- `CoreSelfTest`: PASS.
- `GogoAnimeSource` compiled with JDK 21 against a minimal local `JsonHttpClient` stub: PASS.
- Lazy GogoAnime direct-series-probe regression: PASS; the Mushoku Tensei romaji candidate resolves first to `/series/mushoku-tensei-isekai-ittara-honki-dasu/` and remains marked as an untrusted `discoveryDirect` probe until page identity validation.
- Player-isolation JavaScript parsed with Node.js `--check`: PASS.
- The player iframe is no longer navigated as a top-level WebView URL; it is resized in-place to preserve provider request context.
- Native JavaFX Windows playback was not executable in this Linux environment; `RUN.bat` / `BUILD.bat` on Windows remains the runtime acceptance test.

## v1.5.9 validation performed in this environment

- `CoreSelfTest`: PASS.
- `HttpMediaRelay` + `MediaRelaySelfTest` compile and run under JDK 21: PASS.
- Relay is explicitly bound to `127.0.0.1` and exposes `/media.mp4` for MP4 sources: PASS.
- Relay HEAD emulation performs an upstream `Range: bytes=0-1` probe and returns full `Content-Length` plus `video/mp4`: PASS.
- Exact User-Agent + Referer forwarding regression: PASS. The self-test rejects a mismatched User-Agent.
- Synthetic `Origin` removal regression: PASS. The self-test rejects any unexpected Origin header.
- HTTP 206 byte-range passthrough and `Content-Range` preservation: PASS.
- Player-resolver JavaScript (`directMediaSourceScript`, `bestEmbeddedFrameScript`, `stopEmbeddedMediaScript`) parses with Node.js `--check`: PASS.
- GoogleVideo `/videoplayback` resource-timeline detection is present even without an `.mp4` suffix: PASS.
- The project shell wrapper invokes Windows PowerShell, so the complete JavaFX Gradle build cannot run in this Linux environment. The user's Windows `gradlew run` remains the final JavaFX compile/runtime acceptance step.


## v1.5.10 validation performed in this environment

- Corrected the illegal Java escape in `MainWindow.java` line containing the GoogleVideo resource-timeline regex.
- Java text-block smoke test confirms the source emits `/googlevideo\.com\/videoplayback/i` as valid JavaScript.
- `javac` parser scan no longer reports `illegal escape character`; unresolved JavaFX/application symbols are expected without the full Gradle dependency graph.
- Complete Gradle compilation remains a Windows acceptance step because the packaged `gradlew` delegates to PowerShell.


## v1.5.12 lambda validation

- `PlayerController` no longer captures the reassigned local `mediaUri` from a lambda.
- The callback captures immutable `resolvedMediaUri`, satisfying Java's effectively-final rule.
- A focused JDK 21 compile of `PlayerController.java` against API-compatible local stubs is used to verify the lambda fix during packaging.
- `HttpMediaRelay` + `MediaRelaySelfTest` re-run under JDK 21 after the patch: PASS.
- Full Gradle/JavaFX runtime acceptance remains the Windows `gradlew run` step.

## v1.5.12 focused validation

- `CoreSelfTest.java`: PASS, including strict Gogo series/episode slug rejection.
- `MediaRelaySelfTest.java`: PASS, including `206 Partial Content`, exact `Content-Range`, and nearby-range cache reuse.
- `MainWindow.java`: syntax/escape/changed-method-arity scan PASS. Full JavaFX type-check remains a Windows/Gradle runtime validation because JavaFX is not installed in this container.
- Static source lock assertions: PASS (`verifiedSeriesSlug`, same-slug episode filtering, exact episode-page revalidation).
