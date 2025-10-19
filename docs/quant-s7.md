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
