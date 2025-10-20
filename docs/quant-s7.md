# S7.1 Sampling & ROI Overlay

## Включение и запуск

* Флаг функции: `FEATURE.S7_SAMPLING` (диагностический лог `FEATURE.flag`). Включён по умолчанию.
* На экране импорта включите чекбокс **Sampling (S7.1)**. При первом включении строится выборка и веса; при последующих переключениях переиспользуется текущий результат.

## Диагностика и артефакты

* Логи: `diag/session-*/log.jsonl` (события `PALETTE.sampling.*`, `PALETTE.overlay.ready`).
* Выборка: `diag/session-*/palette/sampling.json` (параметры запуска, гистограмма зон, coverage-флаг).
* Визуализация: `diag/session-*/palette/sampling_overlay.png` — heatmap и точки в разрешении предпросмотра.

## Live overlay

* Переключатель `Sampling (S7.1)` включает/отключает оверлей без повторного расчёта.
* Оверлей масштабируется под превью (`ImageView.fitCenter`), heatmap отражает вес семплов, цвет точек кодирует ROI-зону.

# S7.2 Palette Init (K₀)

## Цель и входные данные

* Флаг функции: `FEATURE.S7_INIT` (`FEATURE.flag` в логах). Для fallback-якорей — `FEATURE.S7_INIT_FALLBACKS`.
* На входе используется последний `S7SamplingResult` (результат шага S7.1) и seed из `sampling.json`.

## Выход и артефакты

* Палитра и отчёт: `diag/session-*/palette/palette_init.json` (состав K₀, роли, параметры, заметки).
* Ленточка предпросмотра: `diag/session-*/palette/palette_strip.png` — swatch-лента в порядке role→L→h.
* Таблица ролей: `diag/session-*/palette/palette_roles.csv` — экспорт с ΔE, protected-флагами и RGB.

## Live Preview

* Кнопка **Init K₀** появляется после завершения S7.1 и запускает `S7Initializer.run(...)` в фоне.
* Чекбокс **Палитра K₀** включает/отключает ленточку без пересчёта.
* В статусе выводится итог: размер палитры, anchors, минимальный spread и факт клиппинга.

## Логи

* Основные события: `PALETTE.init.start/done`, `PALETTE.anchors.detect`, `PALETTE.modes.pick`, `PALETTE.spread.enforce`, `PALETTE.roles.assign`, `PALETTE.overlay.palette.ready`.
* Артефактные ошибки — `PALETTE.palette.io.fail`, вычислительные — `PALETTE.init.fail`.
