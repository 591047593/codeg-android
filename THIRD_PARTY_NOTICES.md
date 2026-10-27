# Third-party notices

## Project artwork

Agent avatars use project-owned, text-only monograms and a generic application
palette. This repository does not redistribute third-party agent logos or brand
artwork.

## Gradle Wrapper

The Gradle Wrapper scripts and `gradle-wrapper.jar` are generated from the
[Gradle project](https://github.com/gradle/gradle) and are distributed under the
Apache License 2.0. Their existing source notices are retained.

## Product names and marks

This application uses third-party product names and visual marks only to
identify software and services with which it interoperates. Those names and
marks remain the property of their respective owners. Their inclusion does not
imply affiliation, sponsorship, or endorsement of this project.

The Apache License 2.0 applies to copyrightable project material; it does not
grant permission to use third-party trade names, trademarks, service marks, or
product names except as required for reasonable and customary description of
the origin of the work and reproduction of notices.

## Embedded Public Suffix List data

OkHttp 4.12.0 contains `publicsuffixes.gz`, compiled from the
[Public Suffix List](https://publicsuffix.org/list/public_suffix_list.dat). That
data is licensed under the Mozilla Public License 2.0. The exact source snapshot
used by OkHttp 4.12.0 is available in its
[tagged source tree](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/test/resources/okhttp3/internal/publicsuffix/public_suffix_list.dat).

The generated notice bundle includes OkHttp's notice and the full MPL-2.0 text.
See `config/licenses/EMBEDDED_COMPONENTS.md` for the reviewed component record.

## Bundled typefaces

The app ships two variable fonts as `res/font` resources, so they are not part
of the Gradle dependency graph and do not appear in the dependency inventory
above:

| File | Typeface | Upstream | License |
| --- | --- | --- | --- |
| `res/font/inter_variable.ttf` | Inter | <https://github.com/rsms/inter> | SIL OFL 1.1 |
| `res/font/jetbrains_mono_variable.ttf` | JetBrains Mono | <https://github.com/JetBrains/JetBrainsMono> | SIL OFL 1.1 |

Inter is the default interface font and JetBrains Mono is used for code and
inline commands, matching the fonts the codeg desktop client defaults to. Both
are redistributed unmodified; their copyright notices and the full SIL Open
Font License 1.1 text are in `config/licenses/OFL-1.1.txt` and are included in
the generated notice bundle.

The SIL Open Font License requires that font software be redistributed only
under that license, so these two files are not covered by this project's
Apache License 2.0. That restriction applies to the font files themselves: OFL
places no condition on text rendered with them.

## Dependencies

Runtime and build dependencies retain their own licenses. Direct dependency
coordinates and pinned versions are declared in `gradle/libs.versions.toml`.
The checked-in
[release-runtime dependency inventory](third-party-licenses/DEPENDENCIES.md)
records the resolved artifacts, versions, declared licenses, project URLs, and
embedded license files. Supplemental records cover licensed content embedded
inside an otherwise differently licensed artifact and artifact-specific license
decisions. The report reads each artifact's immediate POM rather than unioning
licenses from broader parent projects; the rationale for Jakarta Inject 2.0.1
is recorded in `config/licenses/LICENSE_REVIEW.md`. The complete notice bundle
is packaged with the application and can be opened from Settings. Regenerate it
with:

```bash
./gradlew :app:syncThirdPartyLicenseReport --no-build-cache --rerun-tasks
```

Dependencies retain their original copyright notices and license terms.
Downstream distributors remain responsible for verifying the report against the
exact artifacts they ship.
