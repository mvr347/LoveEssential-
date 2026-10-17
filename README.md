# LoveEssential

Модульный плагин для **Paper / Purpur 26.2+ и Java 25**. NPC, редакторы, маршруты и диалоги, оформление списка игроков, общая SQLite-база и интеграции с существующим LoveCore.

## Сборка и установка

```bash
./mvnw verify
```

Готовый плагин: `plugin/target/LoveEssential-2.0.0-SNAPSHOT.jar`. API для разработчиков: `api/target/loveessential-api-2.0.0-SNAPSHOT.jar`.

1. Установить Java 25 и Paper/Purpur 26.2. Сборка проверяется против Paper API `26.2.build.100-stable`; новые версии требуют проверки перед обновлением рабочего сервера.
2. Установить современный LoveCore. API закреплён на commit [`5a99cdb141`](https://github.com/mvr347/LoveCore/tree/5a99cdb1413be77d4b7a2dcbfedcc06f62d1c7ba). Старые сборки ядра с другим экономическим API не подходят.
3. Поместить JAR LoveEssential в `plugins/` и запустить сервер.
4. Настроить `plugins/LoveEssential/config.yml` и `lang.yml`.

NPC используют нативные сущности Paper, включая **MANNEQUIN** со скином игрока. Citizens, ProtocolLib и PacketEvents для этого backend не требуются. Манекен — отдельная сущность Minecraft; он не добавляется фиктивным игроком в tablist.

## Создать NPC в игре

Администратору с `loveessential.admin`:

```text
/npc wizard
```

Семь экранов: идентификатор и шаблон → внешность и экипировка → доступ и взаимодействие → поведение → маршрут → диалоги → расписание и физика. В мастере доступны скин, размер, поза, возраст, шесть слотов экипировки, тип клика, требование прямой видимости, задержка и звук взаимодействия, коллизия, гравитация, неуязвимость, двери и ограничения маршрута. Стандартные условия и действия выбираются в меню, затем мастер запрашивает параметры. Текст вводится в чат и перехватывается мастером; альтернативный ввод — `/le input значение`.

```text
/npc create gate_guard
/npc set name <gold>Страж ворот
/npc set type MANNEQUIN
/npc set skin Notch
/npc set main-hand IRON_SWORD
/npc here
/npc save
/npc publish
```

`save` сохраняет черновик, `publish` проверяет и публикует NPC. Черновик восстанавливается после выхода/перезапуска. При редактировании существующего NPC его ID остаётся неизменным. Перед открытием другого черновика текущий следует отменить через `/npc discard`.

```text
/npc edit gate_guard
/npc clone gate_guard market_guard
/npc preview
/npc list
/npc select
/npc debug gate_guard
/npc delete gate_guard confirm
```

Клонирование открывает новый черновик с отдельными ключами локализации. Импорт/экспорт: `/npc export gate_guard`, затем `/npc import gate_guard new_guard`. Файлы находятся в `plugins/LoveEssential/exports/`; импорт открывает черновик, который нужно проверить и опубликовать.

### Маршруты

В активном черновике:

```text
/npc path add
/npc path mode PING_PONG
/npc path point 1 wait 60
/npc path point 1 speed 1.2
/npc path point 1 radius 0.6
/npc path remove 2
/npc path move 3 1
/npc path undo
/npc path redo
/npc path record
/npc path tool
/npc path show
/npc path test
/npc path pause
/npc path resume
/npc path step
```

`record` переключает запись перемещения с расстоянием между точками не менее четырёх блоков. Инструмент добавляет текущую позицию по клику. Подсветка и тестовый экземпляр видны только редактору; действия точек в предпросмотре не выполняются.

Режимы: `ONCE`, `LOOP`, `PING_PONG`, `RANDOM`; поведение `movement`: `route`, `follow`, `stationary`. Следование настраивается через `follow-player` и `follow-distance`. Без имени NPC выбирает ближайшего подходящего игрока. При потере цели останавливается.

Путь ищется ограниченным A* по неизменяемому снимку загруженной области. Переходы через другие миры, полёт, разрушение блоков и принудительная загрузка чанков в этот наземный backend не входят. Длинные маршруты разбиваются на близкие точки. Двери открываются только при `can-open-doors: true`; вода допускается при `avoid-water: false`. Движение применяет небольшие перемещения на серверном потоке.

### Диалоги

```text
/npc dialog node hello <yellow>Чем могу помочь?
/npc dialog node job <gray>Зайди на доску контрактов.
/npc dialog answer hello work job Ищу работу.
/npc dialog answer hello leave end До встречи.
/npc dialog start hello
/npc publish
```

Графы проверяются на отсутствующие переходы и недостижимые узлы. Циклы разрешены, число переходов сессии ограничено. Ответ содержит nonce, узел и ID; сервер повторно проверяет ревизию, доступ и расстояние. Кнопки устаревшего диалога не исполняются.

Расширенные команды принимают JSON. Например:

```text
/npc set access {"type":"reputation","args":{"min":"30"},"children":[]}
/npc set actions [{"type":"lovecontracts:board","args":{}}]
/npc dialog answer-condition hello work {"type":"authenticated","args":{},"children":[]}
```

Команды `answer-actions` и `path point N actions` принимают массив действий. `path point N condition` принимает дерево условия.

### Настройки NPC

Все настройки задаются через `/npc set параметр значение`; наиболее частые доступны в GUI.

| Параметры | Назначение |
|---|---|
| `skin`, `pose`, `scale`, `baby` | Облик, поза, размер, возраст |
| `main-hand`, `off-hand`, `helmet`, `chestplate`, `leggings`, `boots` | Экипировка, материал Bukkit |
| `description-key`, `name-visible`, `glowing`, `silent` | Описание, имя и эффекты |
| `personal`, `visibility`, `access`, `require-auth` | Персональные экземпляры и условия |
| `activation-range`, `interaction-range`, `cooldown-ms` | Дистанции и задержка кликов |
| `look-at`, `look-range`, `pause-on-interact` | Реакция на игрока |
| `movement`, `speed`, `follow-player`, `follow-distance` | Движение |
| `avoid-water`, `can-open-doors` | Навигация |
| `stuck-ticks`, `stuck-policy`, `teleport-on-stuck` | Застревание |
| `enabled-hours`, `hour-from`, `hour-to` | Расписание по игровому времени, 0–23999 |
| `collidable`, `gravity`, `invulnerable`, `enabled` | Физика и включение |

`stuck-policy`: `PAUSE`, `SKIP`, `RETURN_HOME`. Телепорт при возврате включается отдельно через `teleport-on-stuck`; его место проверяется на безопасность и территорию. Скин применяется к MANNEQUIN; особенности других сущностей зависят от их типа.

## Tablist и nametag

Два режима выбираются через `tab.engine`.

| Функция | `native` | `tab` |
|---|---|---|
| Header/footer, контекстные профили | Да | Да |
| Группы, prefix/suffix, сортировка | Да | Да |
| Nametag, коллизия, видимость | Да | Да |
| Анимации, плейсхолдеры Love/PAPI | Да | Да |
| Playerlist/below-name objective | Да | Да |
| Sidebar и несколько bossbar | Да | Да |
| Layout с фиктивными слотами | — | TAB |
| Полная схема условий/групп/пользователей TAB | — | TAB |
| Сетевой/global tablist | — | TAB с его proxy-настройкой |

Режим `native` работает средствами Paper. Учитывает видимость игрока для зрителя, сохраняет предыдущий scoreboard и по умолчанию уважает scoreboard другого плагина.

Для функциональности уровня NEZNAMY TAB установите **TAB и PlaceholderAPI**, затем:

1. Выберите `tab.engine: tab`.
2. Настройте `tab.external.config`, `tab.external.groups`, `tab.external.animations`.
3. Выполните `/le tab export`, затем `/le reload`.

`tab.external` передаёт полную схему настроек установленного TAB: можно добавлять его штатные параметры. Экспорт выполняется только командой администратора, создаёт резервные копии `*.loveessential.bak` и вызывает `tab reload`. LoveEssential в этом режиме не создаёт конкурирующие teams/objectives.

`@lang(tab.header)` преобразуется в `%loveessential_text_tab.header%`. Тексты остаются в `lang.yml`. Переключатели запоминания выбора в поставляемой конфигурации TAB отключены; настройки игроков LoveEssential сохраняет в SQLite.

### Плейсхолдеры в lang.yml

MiniMessage-теги: `<player>`, `<viewer>`, `<world>`, `<visible_online>`, `<max_players>`, `<clan>`, `<reputation>`, `<territory>`, `<ping>`, `<suffix>`, `<afk>`, `<animation:kingdom>`, `<papi:identifier>`.

PAPI: `%loveessential_afk%`, `%loveessential_vanished%`, `%loveessential_authenticated%`, `%loveessential_clan%`, `%loveessential_reputation%`, `%loveessential_text_КЛЮЧ%`.

Значения обычных переменных экранируются. При добавлении текстов мастером генерируются отдельные ключи; неудачная публикация не переписывает тексты активной ревизии.

## Хранение и потоки

`loveessential.db` содержит определения, маршруты, диалоги, черновики, профили, позиции, настройки модулей, историю изменений и журнал публикаций. Структуры NPC сериализованы в JSON внутри SQLite; рабочие `npcs.yml`, `paths.yml` и файлы данных игроков не создаются.

- HikariCP: небольшой пул SQLite, WAL, foreign keys, busy timeout.
- Один последовательный writer на virtual thread с ограниченной очередью.
- Ограниченные асинхронные чтения и I/O на virtual threads.
- ConcurrentHashMap: определения, реестры, сессии.
- Caffeine: профили и вычисления PlaceholderAPI с лимитами размера/времени.
- Отдельный ограниченный CPU-пул A*; Bukkit-мир доступен только на серверном потоке.
- Проверка ревизии в транзакции защищает от перезаписи чужих изменений, в том числе после удаления и повторного создания ID.

`lang.yml` содержит весь собственный отображаемый текст. `config.yml` хранит технические настройки и ссылки на ключи. Экспортные файлы предназначены для переноса, рабочим источником данных остаётся SQLite.

Публикация текста и определения использует журнал подготовки: после сбоя восстанавливаются ключи локализации. Неиспользуемые ключи допустимы. Физическая валюта и произвольные внешние действия не образуют транзакцию с SQLite: для составных торговых операций используйте сервис владельца домена, а не цепочку несвязанных действий.

## Интеграции Love

LoveCore подключён как `provided` API, его классы не копируются в JAR. Сервисы разрешаются при обращении, поэтому поздняя регистрация нового провайдера учитывается.

| Источник | Использование |
|---|---|
| AuthOracle / LoveAuth | Авторизация, запрет действий при недоступном поставщике |
| ReputationOracle / LoveBehavior | Условия и изменения репутации, оформление |
| ProfileOracle / LoveClans | Кланы и условия доступа |
| TerritoryOracle / LoveClaims | Создание, навигация, телепортация |
| CombatState | Запрет телепортации в бою |
| LoveEconomy | Физические монеты, `balance`, `charge`, `give`, банкир |
| LoveNotify | Интерактивные сообщения и диалоги |
| StatBus / LoveLeaderboards | Статистика взаимодействий, ответов и телепортов |
| LoveSubnames | Суффикс через PlaceholderAPI |
| LoveContracts | Действия `lovecontracts:board`, `lovecontracts:player-board`, `lovecontracts:my` |
| LoveShops | Действие `loveshops:banker` |
| LoveChat | Ввод мастера отменяет AsyncChatEvent на LOWEST; `/le input` служит альтернативой |

Адаптеры меню LoveContracts/LoveShops используют проверенные публичные методы соседей. При несовместимой версии они отказывают с диагностикой, без подавления ошибки. Собственные тексты соседних меню принадлежат локализации соответствующего плагина.

Другие Love-плагины и будущий LoveQuests подключаются через реестр условий, действий и приоритетных взаимодействий. Наличие `softdepend` само по себе не объявляется готовой предметной интеграцией. Квестовый провайдер регистрирует взаимодействие с приоритетом выше обычного меню; `true` означает, что он обработал клик.

```java
LoveEssentialService api = Bukkit.getServicesManager().load(LoveEssentialService.class);
var registration = api.registerCondition("lovequests:active", (player, args) -> {
    return quests.hasActive(player.getUniqueId(), args.get("id"))
        ? LoveEssentialService.Verdict.ALLOW
        : LoveEssentialService.Verdict.DENY;
});
var interaction = api.registerInteraction("lovequests:dialog", 1000, (player, npc) -> {
    return quests.openRelevantDialog(player, npc.id());
});
// При отключении соседнего плагина:
registration.close();
interaction.close();
```

Регистрация, сохранение NPC и игровые callback выполняются на серверном потоке. Методы работы с профилем возвращают CompletableFuture. Ключи профиля имеют namespace, например `lovequests.chapter`; произвольная Java-сериализация не применяется. Авторизация, кланы и экономика остаются у их владельцев, в PlayerProfile не создаётся второй источник истины.

## Дополнительные модули

Дома с лимитом, несколько спавнов, TPA/Back с безопасной телепортацией, постоянный vanish, AFK, смена скина по имени, сидение/посадка, ускорение ночи, MOTD, join/quit, простые TextDisplay-голограммы, прямоугольные порталы и частицы следов.

```text
/hologram create market <gold>Рынок
/hologram addline market <gray>Добро пожаловать!
/hologram move market
/portal pos1
/portal create gate
/portal destination gate
```

`pos1` запоминает первый угол, `create` — второй, `destination` — точку назначения. Эти вспомогательные реализации не объявляются полной заменой всех расширенных механик CMI из исходного ТЗ.

Все команды имеют русские алиасы. При конфликте коротких команд используйте `/loveessential:npc` или `/le npc`, для остальных — namespace `/loveessential:home` и т.д.

## Управление и проверка

```text
/le module npc disable
/le module npc enable
/le module tab info
/le reload
/le integrations
/le backup
```

Состояние модулей, заданное командой, сохраняется в SQLite и имеет приоритет над начальным `modules` в конфиге. `/le reload` обновляет конфигурацию и язык; новые размеры пулов требуют перезапуска. Резервная копия SQLite создаётся через `VACUUM INTO`; отдельно сохраняйте `lang.yml` и `config.yml`.

CI собирает JAR на Java 25 и выполняет тесты SQLite, A*, графов, сортировки, экспорта TAB и жизненного цикла/команд через MockBukkit 26.2. Полёт клиента, визуальное поведение манекенов, Purpur и сетевой TAB требуют игрового прогона на целевой сборке сервера; результаты нагрузочного теста не заявляются.
