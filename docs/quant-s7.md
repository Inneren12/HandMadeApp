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

# S7.3 Greedy Palette Growth

## Основные шаги

* Флаг функции: `FEATURE.S7_GREEDY` (`FEATURE.flag` логируется при старте и в `S7Greedy.run`).
* На входе — `S7SamplingResult` (S7.1), `S7InitResult` (S7.2), seed и лимит `kTry` (по умолчанию `S7GreedySpec.kTry_default`).
* Остаточная ошибка для каждой точки выборки: `err_i = ΔE00(okLab_i, okLab_{c*})`, где `c*` — ближайший цвет текущей палитры.
* Важность точки: `imp_i = err_i · w_i` (вес из S7.1).
* Пространство OKLab бьётся на гистограмму `B_L=B_a=B_b=24` (диапазоны `L∈[0,1], a,b∈[-0.5,0.5]`). В каждом бине копится сумма `imp_i` по ROI-зонам.
* Квоты ROI: EDGE 20%, SKIN 20%, SKY 15%, HITEX 25%, FLAT 20% (`S7GreedySpec.roi_quotas`).
* Поправка к приоритету бина: `shortfall(Z) = clamp((quota_target - quota_used)/max(quota_target,ε), 0,1)`, `score = imp_sum · (1 + γ · shortfall)` с `γ=0.5`.

## Кластеры и медиоиды

* Выбирается бин с максимальным score (при равенстве — по `L → a → b`). При выборе второго по важности бина из-за дефицита квоты причина фиксируется как `quota_redress`.
* Кластер включает семплы из выбранного бина, его 6 ортогональных соседей и точки в радиусе `r_ΔE(K) = max(r_min, r0 - r_decay · (K - K0))` (параметры `r0=5`, `r_min=2.5`, `r_decay=0.08`).
* Медиоид — семпл, минимизирующий `Σ_j w_j · ΔE00(m, okLab_j)` внутри кластера (при равенстве берём меньший индекс).
* Новый цвет проецируется в sRGB (с флагом клиппинга) и получает ROI-роль по ведущей зоне кластера.

## Дедупликация и остановка

* Если расстояние до ближайшего цвета палитры `< S7GreedySpec.s_dup` (1.0), кандидат отклоняется с причиной `dup` и `K` не растёт.
* Пустые или слишком маленькие кластеры (`clusterSize < 50`) игнорируются с `reason="empty_cluster"`.
* Алгоритм выполняет до `kTry` итераций и останавливается досрочно после трёх подряд «пустых» добавлений.

## Артефакты

* `palette_greedy_iter.csv` — таблица итераций (`k,zone,impSum,clusterSize,medoid,nearestDe,added,reason`).
* `palette_kXX.json` и `palette_kXX_strip.png` — снапшоты палитры на ключевых `K` (минимум K₀, K₀+4, K_final).
* `residual_heatmap.png` — карта остаточной ошибки для финального `K`.

## Live Preview

* Кнопка **Grow S7.3** запускает `S7Greedy.run(...)` в фоне (параллельно с ProgressBar).
* После завершения обновляются ленточка палитры (расширенная палитра) и оверлей `QuantOverlayView` в режиме residual heatmap (нормировка по `deMed/de95`, совместимое позиционирование с `ImageView.fitCenter`).
* Статус обновляется строкой вида `S7.3 готово: K=…; de95=…; добавлено цветов=…; отклонено (dup)=…`.

## Логи

* `PALETTE.greedy.start/cluster/medoid/iter.done/done` — параметры запуска, выбранные бины, действия с медиоидами и метрики.
* Оверлей: `PALETTE.overlay.residual.ready {w,h,de95,deMed}`.
* Ошибки: `PALETTE.greedy.fail {stage, err}`.
