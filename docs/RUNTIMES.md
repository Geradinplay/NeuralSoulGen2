# Рантаймы NeuralSoul: версии, пути и сборка

Приложение не содержит моделей и бинарников — оно запускает четыре внешних рантайма из каталога `runtime/`. Этот документ описывает, какой рантайм чем является, где код его ожидает и как собрать/заменить его из исходников.

## Сводная таблица

| Рантайм | Путь в проекте | Порт | Версия в репо | Источник |
|---|---|---|---|---|
| llama.cpp (LLM, эмбеддинги, Web Search) | `runtime/llama/llama.exe` | 8080 / 8081 / 8082 | b9789 (b3ce5cedf) | github.com/ggml-org/llama.cpp |
| whisper.cpp (распознавание речи) | `runtime/whisper/whisper-server.exe` | задан в конфиге STT | 1.9.5 | github.com/ggml-org/whisper.cpp |
| Qdrant (долговременная память) | `runtime/data/qdrant.exe` | 6333 (+gRPC 6334) | 1.17.1 (eabee371) | github.com/qdrant/qdrant |
| CosyVoice (cosyvoice.cpp) — TTS | `runtime/cosyvoice/{cpu,cuda-12.4,cuda-13.5,vulkan}/cosyvoice-server.exe` | задан в настройках Голоса (по умолчанию 5020) | пак Lourdle/cosyvoice.cpp | github.com/Lourdle/cosyvoice.cpp |

Все перечисленные пути игнорируются git'ом (см. `.gitignore`: `runtime/`, `model/`).

---

## 1. llama.cpp — генерация, эмбеддинги памяти, Web Search

### Роли в приложении

| Назначение | Порт | Модель по умолчанию | Особенности запуска |
|---|---|---|---|
| Main LLM | 8080 | `model/main/qwen/qwen2.5-coder-14b-instruct-q4_k_m.gguf` | обычный chat-сервер |
| Memory/BGE (эмбеддинги) | 8081 | `model/memory/bgeQ4/bge-m3-Q4_K_S.gguf` | флаги `--embedding --pooling cls` |
| Web Search query model | 8082 | `model/web_search/venus/DR-Venus-4B-RL.Q4_K_M.gguf` | флаг `--reasoning-budget 0` |

Приложение запускает сервер командой вида:

```
llama.exe serve -m <gguf> --host 127.0.0.1 --port <порт> -t 8 \
  --ctx-size ... --parallel 1 --batch-size 512 --ubatch-size 128 \
  --cache-type-k q8_0 --cache-type-v q8_0 -ngl all --main-gpu 0
```

В сборках b8000+ `llama.exe` и `llama-server.exe` — один и тот же сервер.

### Сборка из исходников (Windows + CUDA)

Требуется: Git, CMake ≥ 3.14, Visual Studio 2022 Build Tools (C++ workload), CUDA Toolkit 12.x.

```bat
git clone https://github.com/ggml-org/llama.cpp
cd llama.cpp
cmake -B build -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=ON
cmake --build build --config Release -j 8
```

Готовые бинарники: `build/bin/Release/llama.exe` + рядом CUDA-DLL (`cudart64_*.dll`, `cublas64_*.dll`, `ggml-cuda.dll`, `ggml.dll` и др.). Скопируй `llama.exe` (или `llama-server.exe`) и все `ggml*.dll` / CUDA-DLL в `D:\NEGen2\runtime\llama\`.

Путь к исполняемому файлу задаётся во вкладке «Модели» (Runtime → Executable), поэтому можно держать несколько сборок.

### Альтернатива без сборки

Официальные релизы с готовыми CUDA-сборками: `github.com/ggml-org/llama.cpp/releases` — скачай `llama-b<номер>-bin-win-cuda-*.zip` и распакуй в `runtime/llama`. Текущая версия в проекте — b9789, сборка Clang 20.1.8 (официальный CI).

---

## 2. whisper.cpp — распознавание речи (STT)

### Роли в приложении

Приложение запускает `whisper-server.exe` с моделью из вкладки «Голос → Whisper».
Путь по умолчанию (см. `SttConfig`):

- исполняемый файл: `runtime/whisper/whisper-server.exe`
- модель: `model/stt/whisper/ggml-large-v3-turbo.bin` (формат whisper.cpp `.bin`, не GGUF)

### Сборка из исходников (Windows + CUDA)

```bat
git clone https://github.com/ggml-org/whisper.cpp
cd whisper.cpp
cmake -B build -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=1
cmake --build build --config Release -j 8
```

Нужен только `build/bin/Release/whisper-server.exe` (+ `ggml*.dll`, CUDA-DLL рядом). SDL2 требуется только для примеров `stream`/`talk-llama` — для сервера он не нужен.

Модель large-v3-turbo в формате `.bin` скачивается с HuggingFace (ggml-org/whisper) и кладётся в `model/stt/whisper/`.

---

## 3. Qdrant — векторная база долговременной памяти

### Роли в приложении

- исполняемый файл: `runtime/data/qdrant.exe` (путь зашит в `QdrantRuntimeManager`, порт 6333)
- хранилище: `runtime/data/qdrant_storage` (создаётся автоматически)
- коллекции: `neuralsoul_memory`, `neuralsoul_memory_bge_m3` (+ варианты `_roleplay`)

### Сборка из исходников

Требуется Rust (rustup, toolchain `stable-x86_64-pc-windows-msvc`) и MSVC Build Tools:

```bat
git clone https://github.com/qdrant/qdrant
cd qdrant
git checkout v1.17.1
cargo build --release --bin qdrant
```

Готовый бинарник: `target/release/qdrant.exe` → скопировать в `runtime/data/`.

Сборка Qdrant тяжёлая (~15–30 мин и много RAM). Проще скачать официальный релиз: `github.com/qdrant/qdrant/releases` → `qdrant-x86_64-pc-windows-msvc.zip`.

---

## 4. CosyVoice (cosyvoice.cpp) — озвучка ответов

### Роли в приложении

Пак из четырёх бэкендов (выбирается в настройках Голоса):

| Папка | Бэкенд | Доп. флаг запуска |
|---|---|---|
| `runtime/cosyvoice/cpu/` | CPU | `--cpu` |
| `runtime/cosyvoice/cuda-12.4/` | NVIDIA (CUDA 12) | `--cuda` |
| `runtime/cosyvoice/cuda-13.5/` | NVIDIA (CUDA 13) | `--cuda` |
| `runtime/cosyvoice/vulkan/` | AMD/Intel | `--backend vulkan<индекс>` |

Приложение запускает сервер (порт по умолчанию 5020, задан в настройках Голоса):

```
cosyvoice-server.exe -m <путь к gguf> --api --stream \
  --served-model-name cosyvoice \
  --voice-prompt <имя>=<prompt_speech.gguf> \
  -H 127.0.0.1 -P 5020
```

и общается по OpenAI Speech API: `POST /v1/audio/speech` (JSON `model`, `input`, `voice`,
`speed`, `temperature`, `top_p`, `top_k`, `chunk_tokens`, `stream:true`, `response_format:"wav"`).
Ответ — поток WAV-байтов: 44-байтовый RIFF-заголовок в первом чанке, дальше сырой PCM16
(CosyVoice3 ≈ 24 кГц mono).

Файлы:

- движок: `cosyvoice-server.exe`, `cosyvoice-cli.exe`, `quantize.exe`, `ggml*.dll` и CUDA/Vulkan-DLL — всё внутри пак-папки бэкенда
- веса: `model/tts/cosyvoice/*.gguf` (не входят в пак, качаются отдельно)
- источник пака: `github.com/Lourdle/cosyvoice.cpp` (MIT)
- GGUF-веса: `huggingface.co/Lourdle/Fun-CosyVoice3-0.5B-2512-GGUF` (Apache-2.0)

### Голос (prompt_speech)

CosyVoice требует один раз предзакодировать голос-референс в `prompt_speech.gguf`:

```bat
runtime\cosyvoice\cuda-12.4\cosyvoice-cli.exe --frontend-only ^
  --speech-tokenizer <путь>\speech_tokenizer.onnx ^
  --campplus <путь>\campplus.onnx ^
  --prompt-audio <референс.wav> ^
  --prompt-text "Текст референса" ^
  --prompt-speech-output model\tts\cosyvoice\prompts\nina.gguf
```

ONNX-файлы (speech-tokenizer, campplus) идут в комплекте с весами с HuggingFace.
Сопоставление голос→файл сервер получает через `--voice-prompt имя=файл` при старте;
приложение передаёт имя голоса из настроек Голоса в каждом запросе.

### Сборка из исходников

Требуется: CMake ≥ 3.14, MSVC 2019+, CUDA Toolkit 12.4 или 13.x (для CUDA-бэкенда), Vulkan SDK + `glslc` (для Vulkan).

```bat
git clone --recurse-submodules https://github.com/Lourdle/cosyvoice.cpp.git
cd cosyvoice.cpp

:: CUDA-бэкенд
cmake -B build-cuda -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=ON
cmake --build build-cuda --parallel

:: или Vulkan-бэкенд
cmake -B build-vulkan -DCMAKE_BUILD_TYPE=Release -DGGML_VULKAN=ON
cmake --build build-vulkan --parallel

:: или CPU
cmake -B build-cpu -DCMAKE_BUILD_TYPE=Release
cmake --build build-cpu --parallel
```

Результат: `build*/cosyvoice-server.exe`, `cosyvoice-cli.exe`, `quantize.exe` (плюс `ggml*.dll`, CUDA/Vulkan-DLL). Разложи их по папкам `cpu`, `cuda-12.4`/`cuda-13.5`, `vulkan` в `runtime/cosyvoice/`.

Простейший путь — не собирать, а взять готовый пак из релизов репозитория cosyvoice.cpp.

> Лицензия: движок MIT, веса CosyVoice3 Apache-2.0 — коммерческое использование разрешено.

---

## 5. Модели (GGUF) — где что лежит

| Папка | Что там | Чьё |
|---|---|---|
| `model/main/` | Main LLM, LoRA-адаптеры в `main/llama/lora/` | llama.cpp |
| `model/memory/` | bge-m3 Q4_K_S (эмбеддинги памяти) | llama.cpp (embedding-режим) |
| `model/web_search/` | DR-Venus-4B-RL (генератор поисковых запросов) | llama.cpp |
| `model/stt/whisper/` | whisper large-v3-turbo `.bin` | whisper.cpp |
| `model/tts/cosyvoice/` | CosyVoice3 0.5B GGUF + `prompts/*.gguf` (голоса) | cosyvoice.cpp |

Папки `model/memory`, `model/web_search`, `model/stt`, `model/tts` — служебные: сканер моделей приложения не показывает их содержимое во вкладке «Модели» (см. `AppState.scanModels`).

## 6. Быстрая проверка после замены рантайма

1. Запусти систему кнопкой Start — стартовый диалог показывает каждый шаг: Qdrant → BGE → Main LLM → Web Search.
2. Проверь порты: `http://127.0.0.1:8080/v1/models`, `http://127.0.0.1:8081/health`, `:8082`, `:6333`, порт TTS из настроек Голоса.
3. Слушай/читай лог в System.out: тег `[Runtime]` печатает полную команду запуска llama.cpp, `[Qdrant]` — версию Qdrant.
