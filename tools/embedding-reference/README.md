# Reference data for search by meaning

The tests that check the on-device tokenizer, the model output and search quality compare against data produced by the
official Python tooling (the `tokenizers` library and ONNX Runtime for Python) running the same model file. These scripts
make that data, so it can be regenerated or audited. They are not part of the app or its build.

```
uv venv --python 3.12 venv && uv pip install --python venv/bin/python onnxruntime tokenizers numpy
# From the pinned revision named in app/build.gradle.kts, into this directory:
#   onnx/model_quantized.onnx   (check its SHA-256 against the one in app/build.gradle.kts)
#   tokenizer.json, vocab.txt
venv/bin/python generate_goldens.py     # tokenizer-golden.json, tokenizer-random.json, embedding-golden.json
venv/bin/python build_search_fixture.py # search-fixture.json and the score tables used to choose the similarity floor
venv/bin/python tune_selection.py       # compares query prefixes and floor/window rules on that fixture
```

What the committed test data is:

- `app/src/test/resources/ai/tokenizer-golden.json` and `app/src/androidTest/assets/ai/tokenizer-golden.json`: the hand-picked
  cases, without the one that contains literal `[CLS]` text (the app deliberately treats those as ordinary text).
- `.../tokenizer-random.json`: the first 500 of the 4,000 random-Unicode cases `generate_goldens.py` writes (seeded, so the same
  every time). A larger run is possible with `AISMO_RANDOM_CORPUS=/path/to/tokenizer-random.json ./gradlew testDebugUnitTest`.
- `app/src/androidTest/assets/ai/embedding-golden.json`: reference vectors for ten texts.
- `app/src/androidTest/assets/ai/search-fixture.json`: 40 messages and 18 queries written by hand for this project, with the
  messages each query should find. It was written by the same person who tuned the floor on it, so treat the quality numbers as
  optimistic.
