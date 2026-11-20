package app.codeg.android.shell.display

/** Keep Codeg Web's existing live rate visible below its 30rem container breakpoint. */
internal fun mobileTurnStatsScript(quotedServerOrigin: String): String = """
    (() => {
      if (window !== window.top || location.origin !== new URL($quotedServerOrigin).origin) return;
      const id = 'codeg-shell-turnstats-style';
      if (!document.head || document.getElementById(id)) return;
      const style = document.createElement('style');
      style.id = id;
      // v0.46–v0.47: override the rate and its separator only, not the 24rem file statistics.
      style.textContent = `
        [class~="@container/turnstats"] > div > span.hidden[class~="@[30rem]/turnstats:inline-flex"] {
          display: inline-flex;
        }
        [class~="@container/turnstats"] > div > span.hidden[class~="@[30rem]/turnstats:inline"] {
          display: inline;
        }
      `;
      document.head.appendChild(style);
    })();
""".trimIndent()
