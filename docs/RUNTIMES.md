# Рантаймы NeuralSoul: версии, пути и сборка

Приложение не содержит моделей и бинарников — оно запускает четыре внешних рантайма из каталога `runtime/`. Этот документ описывает, какой рантайм чем является, где код его ожидает и как собрать/заменить его из исходников.

## Сводная таблица

| Рантайм | Путь в проекте | Порт | Версия в репо | Источник |
|---|---|---|---|---|
| llama.cpp (LLM, эмбеддинги, Web Search) | `runtime/llama/llama.exe` | 8080 / 8081 / 8082 | b9789 (b3ce5cedf) | github.com/ggml-org/llama.cpp |
| whisper.cpp (распознавание речи) | `runtime/whisper/whisper-server.exe` | задан в конфиге STT | 1.9.5 | github.com/ggml-org/whisper.cpp |
| Qdrant (долговременная память) | `runtime/data/qdrant.exe` | 6333 (+gRPC 6334) | 1.17.1 (eabee371) | github.com/qdrant/qdrant |
| s2.cpp — Fish Speech S2 Pro (TTS) | `runtime/fish_speech_s2/s2-windows-{cpu,cuda,vulkan}/s2.exe` | задан в настройках Голоса (по умолчанию 5002) | пак коммита `adc43fb9` | github.com/rodrigomatta/s2.cpp |

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

## 4. s2.cpp — Fish Speech S2 Pro (озвучка ответов)

### Роли в приложении

Пак из трёх бэкендов (выбирается в настройках Голоса):

| Папка | Бэкенд | Доп. флаг запуска |
|---|---|---|
| `runtime/fish_speech_s2/s2-windows-cpu/` | CPU | — |
| `runtime/fish_speech_s2/s2-windows-cuda/` | NVIDIA | `-c 0` |
| `runtime/fish_speech_s2/s2-windows-vulkan/` | AMD/Intel | `-v <индекс>` |

Приложение запускает сервер:

```
s2.exe -m <путь к gguf> -t tokenizer.json --server -H 127.0.0.1 -P <порт>
```

и общается по HTTP (PCM16, поток). gRPC приложение не использует — транспорт в настройках Голоса должен быть HTTP.

Файлы:

- движок: `s2.exe`, `ggml*.dll`, CUDA/Vulkan-DLL и `tokenizer.json` — всё внутри пак-папки бэкенда
- веса: `model/tts/fish_speech/s2-pro-q4_k_m.gguf` (не входит в пак, качается отдельно)
- источник пака: `github.com/rodrigomatta/s2.cpp`, коммит `adc43fb9c161b53750133cea95385493f9e77f91`
- GGUF-варианты: `huggingface.co/rodrigomt/s2-pro-gguf`

### Сборка из исходников

Требуется: CMake ≥ 3.14, MSVC 2019+, CUDA Toolkit ≥ 12.4 (для CUDA-бэкенда), Vulkan SDK + `glslc` (для Vulkan).

```bat
git clone --recurse-submodules https://github.com/rodrigomatta/s2.cpp.git
cd s2.cpp

:: CUDA-бэкенд
cmake -B build -DCMAKE_BUILD_TYPE=Release -DS2_CUDA=ON
cmake --build build --parallel

:: или Vulkan-бэкенд
cmake -B build-vulkan -DCMAKE_BUILD_TYPE=Release -DS2_VULKAN=ON
cmake --build build-vulkan --parallel

:: или CPU
cmake -B build-cpu -DCMAKE_BUILD_TYPE=Release
cmake --build build-cpu --parallel
```

Результат: `build/s2.exe` (плюс `s2.dll`/`ggml*.dll`). Структура пак-папки: положи `s2.exe`, собранные DLL и `tokenizer.json` из репозитория в папку бэкенда; веса остаются в `model/tts/fish_speech/`.

Простейший путь — не собирать, а взять готовый пак из релизов репозитория s2.cpp (CPU/CUDA/Vulkan уже собраны сообществом).

> ⚠️ Лицензия: веса Fish Audio S2 Pro — Fish Audio Research License (некоммерческое использование).

---

## 5. Модели (GGUF) — где что лежит

| Папка | Что там | Чьё |
|---|---|---|
| `model/main/` | Main LLM, LoRA-адаптеры в `main/llama/lora/` | llama.cpp |
| `model/memory/` | bge-m3 Q4_K_S (эмбеддинги памяти) | llama.cpp (embedding-режим) |
| `model/web_search/` | DR-Venus-4B-RL (генератор поисковых запросов) | llama.cpp |
| `model/stt/whisper/` | whisper large-v3-turbo `.bin` | whisper.cpp |
| `model/tts/fish_speech/` | s2-pro Q4_K_M GGUF | s2.cpp |

Папки `model/memory`, `model/web_search`, `model/stt`, `model/tts` — служебные: сканер моделей приложения не показывает их содержимое во вкладке «Модели» (см. `AppState.scanModels`).

## 6. Быстрая проверка после замены рантайма

1. Запусти систему кнопкой Start — стартовый диалог показывает каждый шаг: Qdrant → BGE → Main LLM → Web Search.
2. Проверь порты: `http://127.0.0.1:8080/v1/models`, `http://127.0.0.1:8081/health`, `:8082`, `:6333`, порт TTS из настроек Голоса.
3. Слушай/читай лог в System.out: тег `[Runtime]` печатает полную команду запуска llama.cpp, `[Qdrant]` — версию Qdrant.
