# Плейсхолдеры LoveEssential

[Главная](../README.md) · [Команды](COMMANDS.md) · [Права](PERMISSIONS.md) · [Руководство](ADMIN_GUIDE.md)

## Как подключить

Для внешних плагинов установите PlaceholderAPI и перезапустите сервер. Расширение `loveessential` входит в LoveEssential и регистрируется автоматически; отдельная загрузка через eCloud не нужна.

- В чате, внешнем TAB и других PAPI-шаблонах: `%loveessential_nickname%`, `%loveessential_health%`.
- В поддерживаемых шаблонах `lang.yml` LoveEssential: `<nickname>`, `<health>`, `<clan>`.
- Сторонние PAPI-значения внутри этих шаблонов: `<papi:lovesubnames_current>`; знаки `%` внутри тега не ставятся.

Ниже перечислены все поля текущего снимка игрока. Колонка PAPI содержит полное имя для копирования. Числовые дробные значения используют точку и один знак после неё. `true`/`false` — технические значения; варианты `_state` используют `players.yes` и `players.no` из `lang.yml`.

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

## Логические статусы

| PlaceholderAPI | Тег LoveEssential | Значение |
| --- | --- | --- |
| `%loveessential_afk%` | `<afk>` | PAPI: true/false; родной `<afk>`: декоративный статус TAB, см. ниже |
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
| `<animation:kingdom>` | Кадры `tab.animations.kingdom`, смена каждые 500 мс |
| `<animation:имя>` | Кадры `tab.animations.<имя>` |
| `<papi:идентификатор>` | Результат `%идентификатор%` стороннего расширения; учитывает контекст цели и зрителя |

Для текстового «Да/Нет» в карточке AFK используйте `<afk_state>`. `<afk>` предназначен для декоративного суффикса и отличается от `%loveessential_afk%`.

## Тексты из lang.yml для внешнего TAB

`%loveessential_text_<ключ>%` рендерит существующий ключ `lang.yml`. Примеры:

| Плейсхолдер | Источник |
| --- | --- |
| `%loveessential_text_tab.header%` | `tab.header` |
| `%loveessential_text_tab.footer%` | `tab.footer` |
| `%loveessential_text_tab.name%` | `tab.name`, с подстановкой ника при `apply-to-tab: true` |
| `%loveessential_text_tab.groups.admin.name%` | Имя группы администратора |

Результат содержит legacy RGB-форматирование `§x…`. При экспорте `/le tab export` ссылка `@lang(tab.header)` автоматически превращается в `%loveessential_text_tab.header%`.

В PAPI подстановка выполняется для самого игрока: `viewer` и целевой игрок совпадают. Шаблоны встроенного TAB могут использовать отдельного зрителя для реляционных значений.

## Примеры

Карточка профиля или другой поддерживаемый шаблон `lang.yml`:

```yaml
player-card:
  name: '<nickname> <dark_gray>(<real_name>)'
  state: '<gray>Здоровье: <white><health>/<max_health>'
  time: '<gray>В игре: <white><playtime_hours> ч <playtime_minutes> мин'
  clan: '<gray>Клан: <gold><clan> <suffix>'
```

Ссылка в `modules/profile.yml`, внутри `cards`:

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

Для внешнего TAB в `modules/tab.yml`, внутри `external.groups`:

```yaml
_DEFAULT_:
  customtabname: '%loveessential_nickname%'
```

После изменения: `/le tab export`, затем `/le reload`. Нужны TAB и PlaceholderAPI. Для чата используйте `%loveessential_nickname%`, если форматтер поддерживает legacy RGB, либо `%loveessential_nickname_mini%`, если он ожидает MiniMessage. Форматирование `<nickname>` в LoveEssential уже является компонентом: дополнительно разбирать его как строку не нужно.

Проверка через установленный PlaceholderAPI:

```text
/papi parse me %loveessential_nickname%
/papi parse me %loveessential_real_name%
/papi parse me %loveessential_health%
```

## Обновление и доступность

Поля профиля обновляются на серверном потоке с интервалом `refresh-ticks` в `modules/profile.yml`; по умолчанию 40 тиков. При открытии досье снимок обновляется сразу. PAPI читает неизменяемые снимки этих полей, что позволяет запрашивать их асинхронно. Настройки никнейма загружаются отдельно из SQLite и обновляются после успешного сохранения.

После входа дождитесь первой загрузки/обновления. GUI досье и инвентаря работают с онлайн-игроками; после выхода PAPI-значения возвращают пустую строку. Если провайдера клана, репутации, территории или экономики нет, соответствующее поле пустое. `last_seen` — время снимка, а не отдельная история всех входов и выходов.

Координаты и территория в GUI защищены настройкой права карточки. PAPI-поля `world`, `x`, `y`, `z`, `territory` предоставляются серверным плагинам без отдельной проверки зрителя: используйте их в соответствующих административных шаблонах.

Плейсхолдеры поддерживаются там, где шаблон проходит через рендерер LoveEssential, например в TAB и карточках профиля. Произвольная строка YAML или обычное сообщение `lang.yml` сама по себе не включает PAPI.
