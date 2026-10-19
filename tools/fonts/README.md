# Bundled font and icons

## Manrope (app/src/main/res/font)

Source: https://github.com/google/fonts/tree/main/ofl/manrope (`Manrope[wght].ttf`), SIL Open
Font License 1.1. Three static weights are cut from the variable font and trimmed to the
characters LiveKey shows, keeping every layout feature (tabular figures included) and the
copyright and licence fields:

```sh
pip install fonttools
for w in 400:regular 500:medium 600:semibold; do
  python3 -m fontTools.varLib.instancer -q 'Manrope[wght].ttf' wght=${w%%:*} -o full.ttf
  python3 -m fontTools.subset full.ttf \
    --unicodes='U+0020-007E,U+00A0-00FF,U+2013-2014,U+2018-201D,U+2022,U+2026,U+2212,U+00B0' \
    --layout-features='*' --name-IDs='*' \
    --output-file=app/src/main/res/font/manrope_${w##*:}.ttf
done
```

Add characters to `--unicodes` before using them in a string (a missing glyph falls back to the
phone's font for that character).

## Icons (app/src/main/res/drawable/ic_*.xml)

Material Symbols Rounded, weight 400, from the npm package `@material-symbols/svg-400`
(Apache License 2.0). Each SVG (viewBox `0 -960 960 960`) becomes a vector drawable with the
same path inside `<group android:translateY="960">`.
