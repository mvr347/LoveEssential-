# LoveEssential

**Модульный плагин для Paper / Purpur 26.2+ на Java 25:** NPC с мастером создания, маршрутами и диалогами; TAB; дома и телепортация; личные сообщения; никнеймы с цветами и градиентами; просмотр инвентаря и досье игроков. Плагин использует LoveCore и подключает сервисы экосистемы Love.

[![Build and test](https://github.com/mvr347/LoveEssential-/actions/workflows/build.yml/badge.svg)](https://github.com/mvr347/LoveEssential-/actions/workflows/build.yml)

**Документация:** [меню, крафты и стартовый набор](docs/CUSTOM_CONTENT.md) · [все команды и алиасы](docs/COMMANDS.md) · [все плейсхолдеры](docs/PLACEHOLDERS.md) · [права доступа](docs/PERMISSIONS.md) · [руководство администратора](docs/ADMIN_GUIDE.md)

## Возможности

| Раздел | Что доступно |
| --- | --- |
| Свои меню | Редактор кнопок, описаний, действий, условий и прав; тексты в lang.yml |
| Свои крафты | Редактор 3×3, рецепты с формой/без формы, точные ингредиенты и результат с метаданными |
| Первый вход | Перенос набора LoveTweaks, GUI настройки, ожидание LoveAuth и свободного места, защита повторов |
| Панель управления | `/le` — настройки и включение модулей; отдельные GUI функций и каталог `/le help` |
| NPC | Создание, удаление с подтверждением, черновики, независимые действия, скины MANNEQUIN, экипировка, условия доступа, расписания |
| Маршруты и диалоги | Мастер пути, запись точек, undo/redo, предпросмотр, граф диалогов с условиями и действиями |
| TAB | Header/footer, группы, prefix/suffix, сортировка, nametag, sidebar, bossbar; экспорт настроек для внешнего TAB |
| Личные сообщения | `/msg`, `/reply`, игнор-лист, отключение приёма сообщений и настройки через GUI |
| Никнеймы | Цвета, HEX, градиенты из 2–5 цветов, предпросмотр, сброс оформления или всего никнейма |
| Инвентарь | Живой просмотр инвентаря онлайн-игрока и перенос предметов с отдельным правом на редактирование |
| Досье игрока | Текущий скин, здоровье, опыт, статистика, время игры, клан, репутация, монеты и настраиваемые карточки |
| Essentials-функции | Дома, спавны, TPA, Back, vanish, AFK, смена скина, сидение, посадка на сущность |
| Мир и оформление | Порталы, TextDisplay-голограммы, ускорение ночи, частицы следов, join/quit и MOTD |
| Данные и язык | SQLite, отдельные конфиги модулей, `lang.yml`, резервная копия базы, API для других плагинов |

NPC работают на нативных сущностях Paper, включая MANNEQUIN со скином игрока. Для этого backend не нужны Citizens, ProtocolLib или PacketEvents.

## Установка

1. Подготовьте **Java 25** и **Paper / Purpur 26.2+**. API сборки: `26.2.build.100-stable`.
2. Установите совместимый [LoveCore](https://github.com/mvr347/LoveCore). Сборка использует API commit [`5a99cdb141`](https://github.com/mvr347/LoveCore/tree/5a99cdb1413be77d4b7a2dcbfedcc06f62d1c7ba).
3. Получите JAR из [GitHub Actions](https://github.com/mvr347/LoveEssential-/actions/workflows/build.yml): успешный запуск → артефакт `LoveEssential-Java25`. Выберите запуск для нужной ветки или PR.
4. Поместите `LoveEssential-2.0.0-SNAPSHOT.jar` в `plugins/` и перезапустите сервер.
5. Для настройки модулей откройте `/le` от администратора. Выдайте `loveessential.admin` или отдельные [права](docs/PERMISSIONS.md).

Для PlaceholderAPI-плейсхолдеров установите **PlaceholderAPI**: расширение `loveessential` регистрируется самим плагином. Дополнительная загрузка расширения через eCloud не требуется. Для режима внешнего TAB установите также **TAB от NEZNAMY** и настройте `engine: tab` в `modules/tab.yml`.

## Быстрый старт

| Задача | Команда |
| --- | --- |
| Настроить модули / открыть каталог команд | `/le` / `/le help` |
| Создать своё меню / рецепт | `/le gui create market` / `/le recipes create sigil` |
| Настроить предметы первого входа | `/le starter edit` |
| Создать NPC через мастер | `/le npc create guard` |
| Удалить опубликованного NPC | `/le npc delete guard confirm` |
| Сохранить дом / вернуться | `/le sethome base` / `/le home base` |
| Написать игроку / ответить | `/le msg Alice Привет!` / `/le reply Привет!` |
| Настройки сообщений и игнор-лист | `/le ignore` |
| Выбрать ник, цвет или градиент в GUI | `/le nick` |
| Установить градиент | `/le nick gradient #ff6699 #55ffff` |
| Сбросить только оформление / весь ник | `/le nick color reset` / `/le nick reset` |
| Посмотреть / изменить чужой инвентарь | `/le invsee Alice` / `/le invsee Alice edit` |
| Открыть досье | `/le profile Alice` |
| Настроить модуль / TAB | `/le module nickname` / `/le tab` |

Все зарегистрированные команды работают самостоятельно и через `/le`: `/nick` и `/le nick`, `/home` и `/le home`, русские алиасы — аналогично. При конфликте с другим плагином используйте `/loveessential:loveessential <команда>`. TAB дополняет подкоманды, имена игроков, ID NPC, названия домов и значения параметров.

## Плейсхолдеры

| PlaceholderAPI | Значение |
| --- | --- |
| `%loveessential_nickname%` | Цветной никнейм с RGB/градиентом |
| `%loveessential_nickname_plain%` | Никнейм без оформления |
| `%loveessential_real_name%` | Настоящее имя аккаунта |
| `%loveessential_clan%` | Тег клана |
| `%loveessential_reputation%` | Репутация |
| `%loveessential_health%` | Здоровье |
| `%loveessential_playtime_hours%` | Полные часы игры |
| `%loveessential_skin_url%` | URL текущего скина |
| `%loveessential_text_tab.header%` | Текст `tab.header` из `lang.yml` |

В шаблонах LoveEssential используйте MiniMessage-теги: `<nickname>`, `<real_name>`, `<health>`, `<clan>`, `<animation:kingdom>` и `<papi:идентификатор>`. Полный список, форматы значений и примеры для чата/TAB — в [справочнике плейсхолдеров](docs/PLACEHOLDERS.md).

## Настройки и данные

```text
plugins/LoveEssential/
├── config.yml          # Общие параметры
├── lang.yml            # Собственные тексты и шаблоны
├── modules/            # Настройки каждого модуля
│   ├── npc.yml
│   ├── tab.yml
│   ├── messages.yml
│   ├── nickname.yml
│   ├── inventory.yml
│   ├── profile.yml
│   ├── customgui.yml
│   ├── crafting.yml
│   └── starter.yml
├── loveessential.db    # NPC, черновики, профили и состояния
├── backups/            # Резервные копии SQLite
└── exports/            # Экспорт NPC для переноса
```

Доступны 22 модуля: `npc`, `tab`, `home`, `spawn`, `teleport`, `vanish`, `afk`, `skin`, `sitride`, `nightskip`, `portal`, `hologram`, `traces`, `joinquit`, `motd`, `messages`, `inventory`, `profile`, `nickname`, `customgui`, `crafting`, `starter`.

`/le module` открывает список: ЛКМ — настройки, ПКМ — включение/отключение. Состояние, заданное командой или GUI, сохраняется в SQLite и имеет приоритет над `enabled` в файле модуля. `/le reload` перечитывает конфиги и язык. Старый монолитный конфиг переносится автоматически с копией `config.yml.pre-modules.bak`.

`/le backup` создаёт копию SQLite. Для полной резервной копии сохраняйте также `lang.yml`, `config.yml` и `modules/`. Настройки и снимки профилей хранятся в SQLite; ванильный инвентарь сохраняет Minecraft. История личной переписки в базу не записывается.

## Интеграции

Обязательный LoveCore предоставляет авторизацию, кланы, репутацию, территорию, экономику, уведомления и статистику через зарегистрированные сервисы. LoveEssential использует LoveAuth, LoveClans, LoveBehavior, LoveClaims, LoveEconomy, LoveNotify и StatBus при наличии соответствующих провайдеров. LoveSubnames подключается через PlaceholderAPI; для LoveContracts и LoveShops есть действия открытия меню.

Сторонний чат может использовать Bukkit `displayName` или `%loveessential_nickname%`. API содержит отменяемое `PrivateMessageEvent` для фильтров личных сообщений; для LoveChat/LoveChatFilter нужен обработчик этого события. Наличие плагина в `softdepend` означает порядок загрузки, а доступные предметные интеграции перечислены в [руководстве](docs/ADMIN_GUIDE.md#интеграции-love).

## Сборка и API

```bash
./mvnw -B verify
```

Сборка требует JDK 25. Результат: `plugin/target/LoveEssential-2.0.0-SNAPSHOT.jar`; API: `api/target/loveessential-api-2.0.0-SNAPSHOT.jar`. GitHub Actions собирает плагин и запускает тесты на Java 25.

Хранилище использует SQLite, HikariCP и ограниченные очереди чтения/записи на virtual threads; кеши — Caffeine и ConcurrentHashMap. Bukkit-операции с миром и инвентарями выполняются на серверном потоке. Примеры API, NPC, маршрутов, диалогов и экспорта TAB находятся в [руководстве администратора](docs/ADMIN_GUIDE.md).

## Управление модулями и универсальные NPC

Главная панель `/le` предназначена только для настройки, включения и выключения модулей. Управление объектами открывается отдельно: `/le npc`, `/le portal`, `/le hologram`; личные меню — через `/le nick`, `/le profile`, `/le msg`.

У NPC нет профессии или категории. Внешность, движение, взгляд, действия и диалоги назначаются независимо; один NPC может сочетать несколько возможностей. Мастер показывает объяснение каждого параметра. Список точек маршрута позволяет выбрать точку, перенести её, изменить ожидание/скорость/радиус, поменять порядок и удалить с подтверждением. В списке назначенных действий можно переставлять и удалять отдельные действия.

Числовые настройки модулей изменяются кнопками или точным вводом. Движок TAB, параметры bossbar и материалы карточек выбираются в GUI. В запросе ввода в чат указаны редактируемый параметр, пример и текущее значение; кнопка «ОТМЕНИТЬ ВВОД» возвращает в меню. Старая кнопка отмены не влияет на новый запрос.

Взгляд NPC работает во время паузы движения после клика и в предпросмотре. В `modules/npc.yml` раздел `tracking` задаёт `update-ticks` (1–20; по умолчанию 2) и `max-turn-degrees` (1–180; по умолчанию 30). Это поворот модели, без телепортации для слежения.
