# The mathematics of KeyCatch

`KeyCatch-math.pdf` documents the analysis engine (`com.donfluids.music.dsp`) stage by stage,
with the equations and the constants the code uses.

To rebuild it after editing `keycatch-math.html` or the figures:

```sh
cd docs/math
npm install      # KaTeX and Playwright; set PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 to use an existing Chromium
npm run build    # writes KeyCatch-math.pdf
```

Equations are written as `$$…$$` (display) and `\(…\)` (inline) and rendered by KaTeX at build
time. Figures live in `figures/` and are inlined by `<!--include: …-->`. Set `CHROMIUM` to the
path of a Chromium binary if Playwright cannot find one. When the engine's constants change,
update the document and Appendix A with them.
