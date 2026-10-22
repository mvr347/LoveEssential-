# Плейсхолдеры LoveEssential

[Главная](../README.md) · [Команды](COMMANDS.md) · [Права](PERMISSIONS.md) · [Руководство](ADMIN_GUIDE.md)

## Как подключить

Для внешних плагинов установите PlaceholderAPI и перезапустите сервер. Расширение `loveessential` входит в LoveEssential и регистрируется автоматически; отдельная загрузка через eCloud не нужна.

- В чате, в TAB и других PAPI-шаблонах: `%loveessential_nickname%`, `%loveessential_health%`.
- В поддерживаемых шаблонах `lang.yml` LoveEssential: `<nickname>`, `<health>`, `<clan>`.
- Сторонние PAPI-значения внутри этих шаблонов: `<papi:lovesubnames_current>`; знаки `%` внутри тега не ставятся.

Ниже перечислены все поля текущего снимка игрока. Колонка PAPI содержит полное имя для копирования. Числовые дробные значения используют точку и один знак после неё. `true`/`false` — технические значения; варианты `_state` используют `players.yes` и `players.no` из локальных `messages` модулей.

## Имена и оформление

| PlaceholderAPI | Тег LoveEssential | Значение |
| --- | --- | --- |
| `%loveessential_player%` | `<player>` | Настоящее имя игрока |
| `%loveessential_uuid%` | `<uuid>` | UUID |
| `%loveessential_display_name%` | `<display_name>` | Bukkit displayName без оформления |
| `%loveessential_real_name%` | `<real_name>` | Настоящее имя аккаунта |
| `%loveessential_nickname%` | `<nickname>` | Ник с цветами/градиентом; PAPI: legacy RGB §x, родной тег: компонент |
| `%loveessential_nickname_plain%` | `<nickname_plain>` | Ник без оформления |
| `%loveessential_nickname_mini%` | `<nickname_mini>` | Ник в формате MiniMessage |
| `%loveessential_nickname_active%` | `<nickname_active>` | Индивидуальное имя или цвета: true/false |
| `%loveessential_nickname_style%` | `<nickname_style>` | default, color или gradient |
| `%loveessential_nickname_colors%` | `<nickname_colors>` | Выбранные HEX-цвета через запятую; пусто для стандартного оформления |
| `%loveessential_skin_url%` | `<skin_url>` | URL текущего скина; пусто, если текстуры нет |
| `%loveessential_skin_model%` | `<skin_model>` | Модель скина, например classic/slim |

## Состояние и опыт

| PlaceholderAPI | Тег LoveEssential | Значение |
| --- | --- | --- |
| `%loveessential_health%` | `<health>` | Текущее здоровье |
| `%loveessential_max_health%` | `<max_health>` | Максимальное здоровье |
| `%loveessential_food%` | `<food>` | Сытость, 0–20 |
| `%loveessential_saturation%` | `<saturation>` | Насыщение |
| `%loveessential_level%` | `<level>` | Уровень опыта |
| `%loveessential_exp%` | `<exp>` | Процент прогресса текущего уровня, округлённый до целого |
| `%loveessential_total_exp%` | `<total_exp>` | Значение Bukkit totalExperience |
| `%loveessential_ping%` | `<ping>` | Задержка в миллисекундах |
| `%loveessential_gamemode%` | `<gamemode>` | survival, creative, adventure или spectator |
| `%loveessential_gamemode_name%` | `<gamemode_name>` | Перевод режима из lang.yml |

## Мир и интеграции

| PlaceholderAPI | Тег LoveEssential | Значение |
| --- | --- | --- |
| `%loveessential_world%` | `<world>` | Имя мира |
| `%loveessential_x%` | `<x>` | Блочная координата X |
| `%loveessential_y%` | `<y>` | Блочная координата Y |
| `%loveessential_z%` | `<z>` | Блочная координата Z |
| `%loveessential_clan%` | `<clan>` | Тег клана через ProfileOracle |
| `%loveessential_reputation%` | `<reputation>` | Репутация через ReputationOracle |
| `%loveessential_territory%` | `<territory>` | Название владельца территории через TerritoryOracle |
| `%loveessential_balance%` | `<balance>` | Физические монеты в инвентаре через LoveEconomy |
| `%loveessential_currency%` | `<currency>` | Имя валюты LoveEconomy |
| `%loveessential_homes%` | `<homes>` | Число сохранённых домов игрока |

## Время и статистика

| PlaceholderAPI | Тег LoveEssential | Значение |
| --- | --- | --- |
| `%loveessential_first_join%` | `<first_join>` | Первый вход, ISO 8601 в UTC |
| `%loveessential_last_seen%` | `<last_seen>` | Время последнего снимка, ISO 8601 в UTC |
| `%loveessential_playtime_seconds%` | `<playtime_seconds>` | Полное игровое время в секундах |
| `%loveessential_playtime_hours%` | `<playtime_hours>` | Полные часы игры |
| `%loveessential_playtime_minutes%` | `<playtime_minutes>` | Остаток минут внутри часа, 0–59 |
| `%loveessential_kills%` | `<kills>` | Убийства игроков |
| `%loveessential_mob_kills%` | `<mob_kills>` | Убийства мобов |
| `%loveessential_deaths%` | `<deaths>` | Смерти |
| `%loveessential_jumps%` | `<jumps>` | Прыжки |
| `%loveessential_damage_dealt%` | `<damage_dealt>` | Нанесённый урон: Bukkit DAMAGE_DEALT / 10 |
| `%loveessential_damage_taken%` | `<damage_taken>` | Полученный урон: Bukkit DAMAGE_TAKEN / 10 |
| `%loveessential_walk_km%` | `<walk_km>` | Пеший путь в километрах: Bukkit WALK_ONE_CM / 100000 |

Дополнительные показатели доступны в снимке онлайн-игрока без синхронного SQL:

| PlaceholderAPI | Значение |
| --- | --- |
| `%loveessential_kd_ratio%` | Убийства / max(1, смерти), одна цифра после точки |
| `%loveessential_sprint_km%` | Пройдено бегом, км |
| `%loveessential_swim_km%` | Проплыто, км |
| `%loveessential_fly_km%` | Пролетено на элитрах, км |
| `%loveessential_time_since_death_hours%` | Игровые часы после смерти |
| `%loveessential_blocks_mined%` | Сумма MINE_BLOCK по материалам |
| `%loveessential_blocks_used%` | Сумма USE_ITEM для предметов-блоков; не точный счётчик установок |
| `%loveessential_fish_caught%` | Поймано рыбы |
| `%loveessential_animals_bred%` | Разведено животных |
| `%loveessential_villager_trades%` | Сделок с жителями |
| `%loveessential_first_join_local%` | Первый вход, dd.MM.yyyy HH:mm Europe/Kiev |
| `%loveessential_last_seen_local%` | Последний снимок, dd.MM.yyyy HH:mm Europe/Kiev |
| `%loveessential_online_state%` | Локализованное состояние подключения |

Родной тег совпадает с частью после `loveessential_`, например `<kd_ratio>`. Агрегаты блоков обновляются раз в 30 секунд. Эти PAPI-значения рассчитаны на онлайн-снимок; офлайн-профиль отдельно загружает сохранённые сведения из SQLite и помечает их как архивные.

## Логические статусы

| PlaceholderAPI | Тег LoveEssential | Значение |
| --- | --- | --- |
| `%loveessential_afk%` | `<afk>` | PAPI и условия TAB: true/false; родной `<afk>`: декоративный статус TAB, см. ниже |
| `%loveessential_combat%` | — | true/false: игрок в бою (LoveCore); удобно в `conditions` TAB |
| `%loveessential_vanished%` | `<vanished>` | Сохранённое состояние vanish |
| `%loveessential_authenticated%` | `<authenticated>` | Авторизован ли игрок через AuthOracle |
| `%loveessential_combat%` | `<combat>` | Состояние боя через CombatState |
| `%loveessential_flying%` | `<flying>` | Летает ли игрок |
| `%loveessential_flight_allowed%` | `<flight_allowed>` | Разрешён ли полёт |

## Переведённые статусы

| PlaceholderAPI | Тег LoveEssential | Значение |
| --- | --- | --- |
| `%loveessential_afk_state%` | `<afk_state>` | Переведённое значение AFK |
| `%loveessential_vanished_state%` | `<vanished_state>` | Переведённое состояние vanish |
| `%loveessential_authenticated_state%` | `<authenticated_state>` | Переведённое состояние авторизации |
| `%loveessential_combat_state%` | `<combat_state>` | Переведённое состояние боя |
| `%loveessential_flying_state%` | `<flying_state>` | Переведённое значение полёта |
| `%loveessential_flight_allowed_state%` | `<flight_allowed_state>` | Переведённое разрешение полёта |

## Теги контекста и анимации

Эти теги доступны в шаблонах LoveEssential; отдельные PAPI-поля с такими именами не регистрируются.

| Тег | Значение |
| --- | --- |
| `<viewer>` | Имя зрителя |
| `<online>` | Число онлайн-игроков, видимых зрителю |
| `<visible_online>` | То же с явным названием |
| `<max_players>` | Лимит игроков сервера |
| `<suffix>` | Оформленный `%lovesubnames_current%` через PlaceholderAPI |
| `<afk>` | `tab.status.afk` для AFK-игрока, иначе пустой компонент |
| `<animation:имя>` | Кадры `animations.<имя>` из `modules/tab/tab.yml`; смена каждые `change-interval` мс (по умолчанию 500) |
| `<condition:имя>` | Текст `yes` или `no` из именованного условия `conditions.<имя>` |
| `<papi:идентификатор>` | Результат `%идентификатор%` стороннего расширения; учитывает контекст цели и зрителя |

Значения PlaceholderAPI кэшируются: `tab.placeholder-refresh.default` миллисекунд, а для отдельных плейсхолдеров — свой интервал.
Замены вывода задаются в `tab.placeholder-output-replacements` (точное значение, диапазон `0-10` или `else`).

Для текстового «Да/Нет» в карточке AFK используйте `<afk_state>`. `<afk>` предназначен для декоративного суффикса и отличается от `%loveessential_afk%`.

## Тексты из lang.yml

`%loveessential_text_<ключ>%` рендерит существующий ключ локальных `messages`. Примеры:

| Плейсхолдер | Источник |
| --- | --- |
| `%loveessential_text_tab.name%` | `messages.tab.name` (шаблон имени, по умолчанию `<player>`), с подстановкой ника при `nickname.apply-to-tab: true` |

Результат содержит legacy RGB-форматирование `§x…`. Внешний TAB больше не нужен: список, шапку, подвал и имена
ведёт встроенный модуль TAB, поэтому прежние ссылки `tab.header`, `tab.footer` и `tab.groups.*.name` удалены.
Оформление групп задаётся свойствами `tabprefix`, `customtabname`, `tagprefix` и т. д. в `modules/tab/tab.yml`
(см. [MODULAR_CONFIGURATION.md](MODULAR_CONFIGURATION.md)).

Для ника в чате используйте `%loveessential_nickname%`, если форматтер поддерживает legacy RGB, либо
`%loveessential_nickname_mini%`, если он ожидает MiniMessage.

## Примеры

Карточка профиля или другой поддерживаемый шаблон `lang.yml`:

```yaml
player-card:
  name: '<nickname> <dark_gray>(<real_name>)'
  state: '<gray>Здоровье: <white><health>/<max_health>'
  time: '<gray>В игре: <white><playtime_hours> ч <playtime_minutes> мин'
  clan: '<gray>Клан: <gold><clan> <suffix>'
```

Ссылка в `modules/profile/profile.yml`, внутри `cards`:

```yaml
identity:
  enabled: true
  material: PLAYER_HEAD
  title-key: player-card.name
  lore-keys:
    - player-card.state
    - player-card.time
    - player-card.clan
```

Проверка через установленный PlaceholderAPI:

```text
/papi parse me %loveessential_nickname%
/papi parse me %loveessential_real_name%
/papi parse me %loveessential_health%
```

## Обновление и доступность

Поля профиля обновляются на серверном потоке с интервалом `refresh-ticks` в `modules/profile/profile.yml`; по умолчанию 40 тиков. При открытии досье снимок обновляется сразу. PAPI читает неизменяемые снимки этих полей, что позволяет запрашивать их асинхронно. Настройки никнейма загружаются отдельно из SQLite и обновляются после успешного сохранения.

После входа дождитесь первой загрузки/обновления. GUI досье и инвентаря работают с онлайн-игроками; после выхода PAPI-значения возвращают пустую строку. Если провайдера клана, репутации, территории или экономики нет, соответствующее поле пустое. `last_seen` — время снимка, а не отдельная история всех входов и выходов.

Координаты и территория в GUI защищены настройкой права карточки. PAPI-поля `world`, `x`, `y`, `z`, `territory` предоставляются серверным плагинам без отдельной проверки зрителя: используйте их в соответствующих административных шаблонах.

Плейсхолдеры поддерживаются там, где шаблон проходит через рендерер LoveEssential, например в TAB и карточках профиля. Произвольная строка YAML или обычное сообщение `lang.yml` сама по себе не включает PAPI.

## Приватность профиля и экосистема

`%loveessential_profile_visible%`, `%loveessential_profile_hidden%`, `%loveessential_activity_enabled%`, `%loveessential_activity_visible%`, `%loveessential_profile_loaded%` возвращают `true`/`false`. `%loveessential_profile_command%` содержит команду открытия цели; `%loveessential_profile_toggle_command%` и `%loveessential_activity_toggle_command%` — команды переключения собственных настроек. Также есть локализованные `profile_visibility_label` и `activity_visibility_label`. [Полная таблица и примеры внешнего меню](PLAYER_PROFILE.md).

Необязательный запасной текст для PAPI: `<papi:loveadaptation_current_name:"—">`. Профиль отображает адаптации и поведение через реальные `loveadaptation_*`, `lovebehavior_*`, `lovesubnames_*`; авторизацию получает через LoveCore. В чужом профиле PAPI вычисляется для цели.
