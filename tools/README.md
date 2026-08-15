# Tools

Development scripts. None of these are needed to build or run the app.

## Conformance vectors

The protocol module's crypto and serialization are validated byte-for-byte
against output from [pyatv](https://github.com/postlund/pyatv), a reference
implementation known to interoperate with real hardware. Writing these layers
against a spec alone is how subtle interop bugs get shipped.

```bash
python -m venv venv && ./venv/bin/pip install pyatv
./venv/bin/python tools/generate_vectors.py       > protocol/src/test/resources/vectors.json
./venv/bin/python tools/generate_plist_vectors.py > protocol/src/test/resources/plistvectors.json
./gradlew :protocol:test
```

The committed vectors are the ones the test suite runs against; regenerate them
only if you intend to review the diff.

## Launcher icon

`generate_icon.py` emits the adaptive-icon vector drawable and an equivalent
SVG. Android vector XML cannot be previewed directly, so rendering the SVG is
how the shipped drawable gets visually verified.

```bash
python tools/generate_icon.py
```
