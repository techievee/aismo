# aismo: third-party notices

Search by meaning bundles one model and one runtime, both MIT licensed. The writing assistant bundles two engines (llama.cpp, MIT, and
LiteRT-LM, Apache-2.0) but no model: the model file is downloaded by the user. The full licence texts ship inside the app
(`app/src/main/assets/licenses/`) and are reproduced here by reference.

| Component | What it is | Licence | Source |
|---|---|---|---|
| BGE-small-en-v1.5 | The sentence-embedding model, by the Beijing Academy of Artificial Intelligence (BAAI) | MIT (`bge-LICENSE.txt`) | https://huggingface.co/BAAI/bge-small-en-v1.5 |
| `model_quantized.onnx` | An int8 ONNX conversion of that model published by Xenova for transformers.js, fetched at build time from a pinned revision and checked against a pinned SHA-256 (see `app/build.gradle.kts`) | derived from the MIT-licensed model | https://huggingface.co/Xenova/bge-small-en-v1.5 |
| `vocab.txt` | The model's WordPiece vocabulary, from the same repository | as the model | https://huggingface.co/Xenova/bge-small-en-v1.5 |
| ONNX Runtime (Android) 1.32.0 | Runs the model on the phone | MIT, Copyright (c) Microsoft Corporation (`onnxruntime-LICENSE.txt`) | https://onnxruntime.ai |
| llama.cpp v1.0.0 (with ggml) | Runs GGUF language models for the writing assistant; built from the pinned source tarball by this app's Gradle build (SHA-256 checked), using only its C API | MIT, Copyright (c) 2023-2026 The ggml authors (`llama.cpp-LICENSE.txt`) | https://github.com/ggml-org/llama.cpp |
| LiteRT-LM (Android) 0.19.1 | Google's on-device language-model runtime, the second writing-assistant engine | Apache-2.0 (`litertlm-LICENSE.txt`); the notices of the libraries compiled into it are in `litertlm-ThirdPartyNotices.txt` (the file the AAR ships) | https://github.com/google-ai-edge/LiteRT-LM |
| Qwen2.5-0.5B-Instruct (GGUF), Granite 4.0 350M (LiteRT-LM) | The two writing-assistant models the app accepts. **Not bundled**: the user downloads them from Hugging Face and the app checks their SHA-256 | Apache-2.0 | https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF, https://huggingface.co/litert-community/granite-4.0-350m-litert-lm |
| ONNX Runtime's own dependencies | Libraries compiled into the ONNX Runtime binary, each with its own notice | see `onnxruntime-ThirdPartyNotices.txt` (the notices file ONNX Runtime publishes; it covers every build, so it lists more than this one uses) | |

Two notes on the bundled runtime:

- The BGE licence text is the one published with the FlagEmbedding project, which is where BAAI releases the BGE models; the model
  card on Hugging Face also states the MIT licence.
- ONNX Runtime ships a usage-telemetry client and asks for the `INTERNET` permission. This app removes the permission and the
  client's startup component in its manifest and switches the client off in code; `PrivacyTest` checks the installed app on every
  test run.
- llama.cpp is built without its `common` library, so none of its HTTP or TLS code is compiled in. The app's APK contains no network function
  imports in any native library that this build adds, which was checked with `readelf`; the missing `INTERNET` permission is what actually prevents network use.
