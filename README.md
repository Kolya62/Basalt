# Basalt

**English** · [Русский](#русский)

All-in-one performance mod for **Minecraft 26.3 (Fabric)**, built around the game's Vulkan renderer.
Basalt optimizes rendering, world generation, server ticking and memory use. Every optimization is a
separate feature that can be switched off on its own.

## Requirements

- Minecraft **26.3**
- Fabric Loader **0.19.5** or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java **25**

Basalt targets the **Vulkan** renderer and switches the game to it on startup (`force_vulkan`).
If Vulkan cannot be created, vanilla falls back to OpenGL. If a startup on Vulkan does not finish
(crash or hang), Basalt stops forcing Vulkan for good.

## Features

Every feature key below can be set to `true` / `false` in the config (see [Configuration](#configuration)).

### Rendering (client)

| Key | What it does |
|---|---|
| `compactvertex` | Stores chunk geometry in a 16-byte vertex format instead of vanilla's 28 bytes. |
| `facecull` | Groups chunk faces by direction and skips the groups that face away from the camera. Needs `compactvertex` and `drawcache`. |
| `drawcache` | Caches each chunk mesh's draw data and reuses the previous frame's draw lists while nothing changes. |
| `gpuculling` | Two-phase Hi-Z occlusion culling of chunk sections on the GPU. `auto` enables it from render distance 24. Needs `drawcache`. |
| `entityculling` | Hides entities and block entities fully behind opaque blocks (ray tests on a background thread). Range: `entityculling.max_distance`. |
| `modelpart` | Faster entity and item model rendering: each cube corner is transformed once, vertices are written in bulk. |
| `textcache` | Reuses the glyph layout of sign text between frames. |
| `tint` | Reuses biome samples between neighbouring blocks when blending grass, foliage and water colours. |
| `presentmode` | Enables the `vsync_mode` option: `fifo` (default, classic vsync) or `mailbox` (lower latency, but renders up to the FPS limit). |
| `swapchain` | Avoids errors and hitches while the window is being resized. |
| `gpuselect` | Picks the GPU by name: `gpu` = part of the device name (e.g. `RTX`, `Radeon`); empty = vanilla choice. |

### World generation and server (client and server)

| Key | What it does |
|---|---|
| `parallelgen` | Runs the FEATURES generation step (trees, ores, decorations) in parallel for chunks whose write areas don't overlap. Threads: `parallelgen.threads` (`0` = auto). |
| `nativenoise` | Computes terrain Perlin noise in a bundled native (Rust) library, verified bit-for-bit against Java at startup. Without the library, Java is used. |
| `collisions` | Faster entity-vs-block collision checks. |
| `entitysections` | Faster lookups of entities near a small box (e.g. mob pushing). |
| `poi` | Skips chunk columns known to have no points of interest (villager workstations, beds, portals). |
| `brain` | Faster mob AI behaviour iteration. |
| `randomtick` | Skips needless fluid checks during random ticks. |
| `chunklevel` | Caches the last chunk ticking-level lookup. |
| `fastmap` | Block states share one compact property table: less memory, cheap property changes. |
| `statecache` | Computes block face sturdiness lazily and shares identical results (faster startup, less memory). |
| `threaddetect` | Replaces the per-section threading detector with a lightweight flag (less memory). |

## Configuration

- File: `config/basalt.properties`. It is created on first launch with every option.
- In game: **Options → Video Settings** opens Basalt's settings screen.
- Changes take effect after a **restart**: a disabled feature's mixins are not applied at all.
- Any option can be overridden at launch with `-Dbasalt.<key>=<value>`, e.g. `-Dbasalt.parallelgen=false`.

## Compatibility

Basalt overlaps with other optimization mods. It declares `breaks` on Sodium, Embeddium, Nvidium,
Lithium, Canary, C2ME, Noisium, FerriteCore, ModernFix, ImmediatelyFast, EntityCulling, MoreCulling,
BadOptimizations, Starlight, ScalableLux and VMP.

## Building

Requires JDK 25 and Gradle 9.7+ (Fabric Loom 1.18).

```sh
gradle build
```

The mod jar is written to `build/libs/`.

`gradle.properties` contains `org.gradle.java.home` with the author's local JDK path. On another
machine, remove that line or point it to your JDK 25.

The native noise library is optional. `gradle buildNatives` builds it from the `native/` directory
(not part of this repository) into `native/dist/`, and everything in `native/dist/` is bundled into the jar.

## Benchmarks and verification

- **Client benchmark:** `gradle runClient -Pbench=<label> [-PbenchArgs="-Dbasalt.<key>=false ..."]`
  joins the singleplayer world `bench` (it must exist in `run/saves/`), warms up, records frame times
  and writes `run/basalt-bench/<label>.txt`. Useful options: `basalt.bench.warmup`, `basalt.bench.duration`,
  `basalt.bench.edit` (blocks changed per frame, a mesh rebuild stress test).
- **World generation benchmark:** `gradle runServer -Pgenbench=<label> [-PgenbenchArgs="..."]` generates an
  N×N chunk square on a fresh world (`basalt.genbench.size`, default 32) and writes
  `run-server/basalt-genbench/<label>.txt`.
- **Verification:** `-Dbasalt.<feature>.verify=true` compares against vanilla and fails loudly on any
  difference. Available for `collisions`, `entitysections`, `poi`, `nativenoise`, `drawcache`, `modelpart`,
  `tint` and `statecache`.
- `-PnoBreaks` builds without the `breaks` section, so other optimization mods can be loaded for comparison.

## License

Basalt is licensed under the **GNU Lesser General Public License v3.0 only** (`LGPL-3.0-only`).
See [`LICENSE`](LICENSE) (LGPL v3) and [`COPYING`](COPYING) (GPL v3, which the LGPL v3 builds on).

---

# Русский

[English](#basalt) · **Русский**

Мод на производительность «всё в одном» для **Minecraft 26.3 (Fabric)**, построенный вокруг
Vulkan-рендерера игры. Basalt оптимизирует рендер, генерацию мира, тики сервера и расход памяти.
Каждая оптимизация — отдельная функция, которую можно выключить независимо от остальных.

## Требования

- Minecraft **26.3**
- Fabric Loader **0.19.5** или новее
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java **25**

Basalt рассчитан на **Vulkan**-рендерер и переключает игру на него при запуске (`force_vulkan`).
Если Vulkan создать не удаётся, ваниль откатывается на OpenGL. Если запуск на Vulkan не завершился
(краш или зависание), Basalt больше не включает Vulkan принудительно.

## Возможности

Каждый ключ ниже можно выставить в `true` / `false` в конфиге (см. [Настройка](#настройка)).

### Рендер (клиент)

| Ключ | Что делает |
|---|---|
| `compactvertex` | Хранит геометрию чанков в 16-байтовом формате вершины вместо ванильных 28 байт. |
| `facecull` | Группирует грани чанка по направлению и пропускает группы, повёрнутые от камеры. Работает вместе с `compactvertex` и `drawcache`. |
| `drawcache` | Кэширует данные отрисовки каждого меша и переиспользует списки отрисовки прошлого кадра, пока ничего не меняется. |
| `gpuculling` | Двухфазный Hi-Z окклюзионный куллинг секций чанков на видеокарте. `auto` включает его с дальности прорисовки 24. Работает вместе с `drawcache`. |
| `entityculling` | Скрывает сущности и блок-сущности, полностью закрытые непрозрачными блоками (проверка лучами в фоновом потоке). Дальность: `entityculling.max_distance`. |
| `modelpart` | Быстрый рендер моделей мобов и предметов: каждый угол куба трансформируется один раз, вершины пишутся пачкой. |
| `textcache` | Переиспользует раскладку глифов текста табличек между кадрами. |
| `tint` | При смешивании цветов травы, листвы и воды переиспользует выборки биомов между соседними блоками. |
| `presentmode` | Включает настройку `vsync_mode`: `fifo` (по умолчанию, классическая вертикальная синхронизация) или `mailbox` (меньше задержка, но рендер идёт до лимита FPS). |
| `swapchain` | Убирает ошибки и подвисания при изменении размера окна. |
| `gpuselect` | Выбор видеокарты по имени: `gpu` = часть названия устройства (например, `RTX`, `Radeon`); пусто — выбор ванили. |

### Генерация мира и сервер (клиент и сервер)

| Ключ | Что делает |
|---|---|
| `parallelgen` | Выполняет этап генерации FEATURES (деревья, руды, декорации) параллельно для чанков, чьи области записи не пересекаются. Потоки: `parallelgen.threads` (`0` = авто). |
| `nativenoise` | Считает перлин-шум рельефа во встроенной нативной (Rust) библиотеке; при запуске результат побитово сверяется с Java. Без библиотеки используется Java. |
| `collisions` | Быстрая проверка столкновений сущностей с блоками. |
| `entitysections` | Быстрый поиск сущностей рядом с небольшой областью (например, при толкании мобов). |
| `poi` | Пропускает столбцы чанков, в которых точно нет точек интереса (рабочие места жителей, кровати, порталы). |
| `brain` | Быстрый перебор поведений ИИ мобов. |
| `randomtick` | Пропускает лишние проверки жидкостей при случайных тиках. |
| `chunklevel` | Кэширует последний запрос уровня тиков чанка. |
| `fastmap` | Состояния блоков используют одну общую компактную таблицу свойств: меньше памяти, дешёвая смена свойств. |
| `statecache` | Лениво вычисляет «прочность» граней блоков и разделяет одинаковые результаты (быстрее запуск, меньше памяти). |
| `threaddetect` | Заменяет детектор многопоточного доступа в каждой секции лёгким флагом (меньше памяти). |

## Настройка

- Файл: `config/basalt.properties`. Создаётся при первом запуске со всеми параметрами.
- В игре: **Настройки → Настройки графики** открывают экран настроек Basalt.
- Изменения применяются после **перезапуска**: миксины выключенной функции не загружаются вовсе.
- Любой параметр можно переопределить при запуске: `-Dbasalt.<ключ>=<значение>`, например `-Dbasalt.parallelgen=false`.

## Совместимость

Basalt пересекается с другими модами на оптимизацию. В нём объявлен `breaks` для Sodium, Embeddium,
Nvidium, Lithium, Canary, C2ME, Noisium, FerriteCore, ModernFix, ImmediatelyFast, EntityCulling,
MoreCulling, BadOptimizations, Starlight, ScalableLux и VMP.

## Сборка

Нужны JDK 25 и Gradle 9.7+ (Fabric Loom 1.18).

```sh
gradle build
```

Jar мода появится в `build/libs/`.

В `gradle.properties` указан `org.gradle.java.home` с путём к локальному JDK автора. На другой
машине удалите эту строку или укажите путь к своему JDK 25.

Нативная библиотека шума необязательна. `gradle buildNatives` собирает её из каталога `native/`
(не входит в этот репозиторий) в `native/dist/`, а всё содержимое `native/dist/` упаковывается в jar.

## Бенчмарки и проверка

- **Клиентский бенчмарк:** `gradle runClient -Pbench=<метка> [-PbenchArgs="-Dbasalt.<ключ>=false ..."]`
  заходит в одиночный мир `bench` (он должен существовать в `run/saves/`), прогревается, записывает
  время кадров и пишет `run/basalt-bench/<метка>.txt`. Полезные параметры: `basalt.bench.warmup`,
  `basalt.bench.duration`, `basalt.bench.edit` (сколько блоков менять за кадр — стресс-тест перестройки мешей).
- **Бенчмарк генерации мира:** `gradle runServer -Pgenbench=<метка> [-PgenbenchArgs="..."]` генерирует
  квадрат N×N чанков в новом мире (`basalt.genbench.size`, по умолчанию 32) и пишет
  `run-server/basalt-genbench/<метка>.txt`.
- **Проверка:** `-Dbasalt.<функция>.verify=true` сверяет результат с ванилью и падает при любом
  расхождении. Доступно для `collisions`, `entitysections`, `poi`, `nativenoise`, `drawcache`, `modelpart`,
  `tint` и `statecache`.
- `-PnoBreaks` собирает мод без секции `breaks`, чтобы для сравнения можно было загрузить другие моды на оптимизацию.

## Лицензия

Basalt распространяется под **GNU Lesser General Public License v3.0 only** (`LGPL-3.0-only`).
См. [`LICENSE`](LICENSE) (LGPL v3) и [`COPYING`](COPYING) (GPL v3, на которой основана LGPL v3).
