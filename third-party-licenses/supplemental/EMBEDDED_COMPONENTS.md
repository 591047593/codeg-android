# Embedded third-party components

## Public Suffix List data in OkHttp 4.12.0

- Parent artifact: `com.squareup.okhttp3:okhttp:4.12.0`
- Packaged component: `okhttp3/internal/publicsuffix/publicsuffixes.gz`
- Component: Public Suffix List
- Source:
  <https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/test/resources/okhttp3/internal/publicsuffix/public_suffix_list.dat>
- Upstream project: <https://publicsuffix.org/>
- License: Mozilla Public License 2.0
- License text: `MPL-2.0.txt`
- Local modifications: none; the compiled data is provided by the unmodified
  OkHttp dependency.

OkHttp's embedded `NOTICE` identifies the same source and license. Both that
notice and the full MPL-2.0 text are included in the generated application
notice bundle.

## Bundled typefaces

Two variable fonts ship inside the APK as `res/font` resources rather than as
Gradle dependencies, so they do not appear in the generated dependency
inventory and need this record instead.

- Packaged component: `res/font/inter_variable.ttf`
- Component: Inter (variable, weights 100–900)
- Source: <https://github.com/rsms/inter/releases/tag/v4.1>, `InterVariable.ttf`
- Upstream project: <https://github.com/rsms/inter>
- License: SIL Open Font License 1.1
- Copyright: Copyright (c) 2016 The Inter Project Authors
- Local modifications: none; the file is redistributed byte-identical.

- Packaged component: `res/font/jetbrains_mono_variable.ttf`
- Component: JetBrains Mono (variable, weights 100–800)
- Source: <https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304>,
  `fonts/variable/JetBrainsMono[wght].ttf`
- Upstream project: <https://github.com/JetBrains/JetBrainsMono>
- License: SIL Open Font License 1.1
- Copyright: Copyright 2020 The JetBrains Mono Project Authors
- Local modifications: none; the file is redistributed byte-identical.

Both copyright notices and the full OFL-1.1 text are in `OFL-1.1.txt` and are
included in the generated application notice bundle.

Because OFL requires that font software be redistributed only under OFL, these
two files are **not** covered by the project's Apache License 2.0. That does not
extend to text rendered with them: OFL explicitly places no condition on
documents or output produced using the fonts.
