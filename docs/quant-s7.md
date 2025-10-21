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

# S7.4 Spread ≥ 3.5 + Budget 2-opt

## Цель и шаги

* Флаг функции: `FEATURE.S7_SPREAD2OPT` (`FEATURE.flag` логируется при обращении и в `S7Spread2Opt.run`).
* На входе — результаты S7.1 (выборка) и S7.3 (палитра после greedy). Цель: поднять минимальный spread палитры до `ΔE00 ≥ 3.5` и при этом улучшить остаточную ошибку без роста `K`.
* Алгоритм формирует матрицу попарных расстояний, выбирает пары с нарушением spread и выполняет «push-apart» (сдвиг навстречу нормали) с шагом не больше `δ_max=0.6`. После сдвига рассматривается локальная 2-медоидная корректировка по семплам пары.
* Для каждой пары оцениваются три варианта (до, push, medoid) по gain-функции `gain = α·ΔΔE95 + β·ΔGBI − μ·Σ|shift|` с константами `α=1.0`, `β=0.6`, `μ=0.05`. Кандидат принимается только при положительном gain и допустимом клипе (`Δclip ≤ 1.0`).
* Пары сортируются по `pscore = (s_min - ΔE)_+ + γ_err·(Imp_i + Imp_j) / Imp_total` (γ_err=0.5), количество пар ограничено профилем устройства (`M_low=12`, `M_mid=24`, `M_high=36`). Тайм-бюджеты: LOW=250 мс, MID=400 мс, HIGH=650 мс.
* Выполняется до двух проходов (по умолчанию — один) или до исчерпания `time_budget_ms`.

## Артефакты и логирование

* Матрицы и нарушения до/после: `dist_matrix_before.csv`, `dist_matrix_after.csv`, `violations_before.csv`, `violations_after.csv`.
* Принятые/отклонённые фиксы: `2opt_fixes.csv` (индексы, ΔE, gain, причина, перемещения).
* Ленточки палитры: `palette_strip_before.png`, `palette_strip_after.png` с подсветкой конфликтных цветов.
* Карты: `affected_before.png` (ambiguity heatmap) и `affected_after.png` (изменившиеся назначения).
* Логи: `PALETTE.spread2opt.start/done`, `PALETTE.spread.violations`, `PALETTE.spread.pair`, `PALETTE.spread.clip`, `PALETTE.2opt.iter`, `PALETTE.overlay.spread.ready`, ошибки — `PALETTE.spread2opt.fail`.

## Live Preview

* На экране импорта появилась кнопка **Spread 2-opt** (после выполнения S7.3). Запуск — асинхронный, прогресс отображается через общий `ProgressBar` и строку статуса.
* Чекбокс **S7.4 До** переключает ленточку и overlay между состояниями «до» (ambiguity heatmap + подсветка конфликтов) и «после» (affected heatmap, обновлённая палитра).
* `QuantOverlayView` получает режим SPREAD и показывает две карты: «ambiguity» (до) и «affected» (после). Строка статуса выводит minΔE, ΔE95, GBI и статистику фиксов.
* При клиппинге новые координаты логируются (`PALETTE.spread.clip`). При преждевременном выходе по бюджету записывается `reason=time_budget`.

# S7.5 Gain Target & Kneedle Stop

## Цель и алгоритм

* Флаг функции: `FEATURE.S7_KNEEDLE` (лог `FEATURE.flag`). Шаг определяет финальный размер палитры `K*` после S7.4.
* Используется выборка `S7SamplingResult` (S7.1) и палитра после spread/2-opt (S7.4). Палитра перебирается по детерминированному порядку цветов.
* Для каждого `k` от `K0` до `K_try` рассчитываются метрики качества: `ΔE95`, `ΔE_med`, прокси-бэндинг `GBI`, прокси-сложность `TC`/`ISL`.

## Метрики и параметры

* Метрики вычисляются с окнами и порогами из `S7KneedleSpec`: `τ_low=2.0`, `τ_high=5.0`, `g_low=0.02` для индикатора ступеней, `median_window=3` для сглаживания F(K).
* Веса целевой функции: `α=1.0`, `β=0.6`, штрафы `λ_TC=0.15`, `λ_ISL=0.10`.
* Формулы метрик описаны в `S7Metrics`: `ΔE95/ΔE_med` по ближайшему цвету без дизеринга, `GBI` по порогам ступеней, `TC` — доля пикселей с отличающимся индексом от моды в N4, `ISL` — островки (на 1000 пикселей) после морфологического открытия 3×3.

## Gain и кумулятивная кривая

* Полезный прирост: `G1(k) = ΔE95(k-1) - ΔE95(k)`, `G2(k) = GBI(k-1) - GBI(k)`.
* Штраф сложности: `P(k) = λ_TC·TC(k) + λ_ISL·ISL(k)`.
* Целевая функция: `gain_penalized(k) = α·G1(k) + β·G2(k) - P(k)`.
* Кумулятив: `F(K) = Σ gain_penalized(k)` для `k ∈ [K0+1, K]`. Перед нормировкой F сглаживается медианным окном размера 3.

## Kneedle и охранители

* Нормировка: `x(K) = (K - K0) / (K_try - K0)`, `y(K) = (F(K) - F(K0)) / (F(K_try) - F(K0))`, линейная хорда `ℓ(x)=x`.
* Разрыв: `D(K) = y(K) - x(K)`. Выбор `K*` — максимум `D(K)` при `D(K) ≥ τ_knee` (по умолчанию 0.03) и `s_norm(K) ≤ τ_s` для трёх подряд точек (`τ_s=0.15`).
* Если колено не найдено: стопы — `gain_penalized < τ_gain` (0.05) три шага подряд (`reason=low_gain_3x`), достижение `K_try` (`reason=k_max`), либо раннее выполнение качества (`ΔE95` ниже порога пресета и `GBI < 0.03`, `reason=early_quality`).

## Артефакты и логирование

* CSV: `diag/session-*/palette/palette_gain.csv` — таблица `k,de95,deMed,gbi,tc,isl,gain,F,D`.
* График: `diag/session-*/palette/kneedle_curve.png` — F и D с отметкой `K*`.
* Финальная палитра: `palette_final_k.json`, `palette_final_strip.png`.
* Превью: `residual_k*_heatmap.png`, `index_preview_k*.png`.
* Логи категории `PALETTE`: `kneedle.start/metrics/gain/pick/done`, плюс `overlay.kstar.ready` после обновления UI.

## Live Preview и UI

* В ImportActivity добавлена кнопка **Finalize K (S7.5)**. Шаг запускается асинхронно, UI не блокируется.
* После завершения обновляются: ленточка палитры `K*`, предпросмотр индекса без дизеринга, residual heatmap (переключатель **Show Residual**).
* Статус содержит строку вида `S7.5: K*=…; reason=…; ΔE95=…; GBI=…; TC=…; ISL=…; Dmax=…; thresholds τ_knee=…, τ_gain=…`.
* Все параметры запуска (`α,β,λ,τ`, seed, `K0`, `K_try`, smoothing) сохраняются в `S7KneedleResult.params` и логируются.

# S7.6 Primary Indexing into K*

## Цель и входные данные

* Флаг функции: `FEATURE.S7_INDEX` (лог `FEATURE.flag`).
* Использует предмасштабированное изображение (`PreScaledImage` из S6), маски зон (`Masks` из S3), а также финальную палитру `palette_final_k.json` и порядок цветов из S7.5.
* Процесс детерминирован: обход пикселей в порядке row-major с фиксированным seed.

## Стоимость присвоения цвета

Для пикселя `p` и цвета `c` используется расширенная стоимость:

```
cost(p,c) = α₀·ΔE00(p,c)
          + β_fz·FZ(p,c)
          + β_edge·EB(p,c)
          + β_skin·SH(p,c)
          − β_coh·COH(p,c)
```

По умолчанию коэффициенты `α₀=1.0`, `β_fz=0.6`, `β_edge=0.5`, `β_skin=0.4`, `β_coh=0.2`, порог кожи `τ_h=8°` (`S7IndexSpec`).

* `FZ(p,c) = ρ(Z_p, role_c)` — штраф за попадание в чужую зону (матрица ρ в `S7IndexSpec`).
* `EB(p,c) = E(p) · mean_{q∈N4(p)} 1{c ≠ c_est(q)}` — разрыв кромки через маску краёв и уже назначенных соседей.
* `SH(p,c) = 1{Z_p=SKIN} · max(0, |Δh(p,c)| - τ_h) / π` — ограничение сдвига тона кожи в Oklab.
* `COH(p,c) = mean_{q∈N4(p)} 1{c = c_est(q)}` — бонус согласованности с соседями.

Назначение индекса — `argmin_c cost(p,c)` с tie-break по порядку палитры, затем по координатам.

## Артефакты и выводы

Результаты сохраняются в `diag/session-*/index/`:

* `index.bin` — бинарный индекс (`IDX1`, u8/u16 в зависимости от `K*`).
* `index_meta.json` — параметры запуска, коэффициенты, тайминги и агрегаты (counts, foreign-zone hits, EB/COH суммы, средняя стоимость).
* `index_preview_k*.png` — цветной рендер по индексам без дизеринга.
* `palette_legend.csv` — легенда индексов (роль, Lab, RGB, hex, protected).
* `cost_heatmap.png` — дополнительная тепловая карта стоимости (по необходимости).

## Логирование

Категория `PALETTE`:

* `PALETTE.index.start` — параметры запуска (`algo`, `K*`, коэффициенты, tile, seed, tier).
* `PALETTE.index.tile` — агрегаты по тайлам (идентификатор, размеры, время, meanCost, EB, COH).
* `PALETTE.index.assign` — итоговые счётчики (bpp, топ частот, foreign zone hits, суммы штрафов/бонусов).
* `PALETTE.index.done` — пути артефактов и размеры.
* Ошибки — `PALETTE.index.fail {stage, err}`.

## Live Preview и UI

* В ImportActivity добавлена кнопка **Index (S7.6)**. Шаг выполняется в фоне и не блокирует UI.
* После завершения загружается `index_preview_k*.png` и показывается в `QuantOverlayView` (режим INDEX). Доступны переключатели **Grid** и **Cost** для сетки и тепловой карты стоимости.
* Строка статуса: `S7.6: index=8/16-bit, K*=…, meanCost=…, foreignZoneHits=…, EBsum=…, time=…ms`.
* Оверлей логирует событие `PALETTE.overlay.index.ready {w,h,index_bpp,preview=true}` после обновления.

## Повторный запуск и целостность

* При повторных запусках с теми же входами `index.bin` и превью бит-идентичны (сканирование и tie-break детерминированы).
* При `K*>255` автоматически выбирается индекс 16-bit. Для высоких штрафов FZ в `index_meta.json` добавляется заметка `note="high_fz_hits"`.
* При нехватке памяти доступна тайлинговая обработка (`tile_w`, `tile_h` в `S7IndexSpec`); тайлы обходятся в порядке row-major с overlap=1.


## Буферные пулы и откат

* Горячие массивы (assign/dither) теперь берутся из пула. Включение/отключение — флаг `FeatureFlags.S7_BUFFER_POOL_ENABLED` (см. `S7Flag.BUFFER_POOL`). При отключении возвращается старое поведение с явными `IntArray`/`FloatArray` аллокациями.
* Для дизеринга в `S7DitherEngine` линии/плоскости ошибок также берутся из пула; fallback управляется тем же флагом.
* При каждом выделении сверх пула логируется метрика `PALETTE.s7.alloc_bytes_hotpath {stage,buffer,bytes,reason,pooling}` — проверяйте её в профилях/логах перед включением на прод.
* Для оперативного rollback достаточно задизейблить `S7_BUFFER_POOL_ENABLED` через настройки фич-флагов (линейные буферы дизеринга по-прежнему контролируются `S7_DITHER_LINEBUFFERS_ENABLED`).
