# Права доступа

[Главная](../README.md) · [Команды](COMMANDS.md) · [Плейсхолдеры](PLACEHOLDERS.md)

**Базовые команды свободны от отдельных permission-проверок:** home/sethome/delhome, spawn, afk, skin, sit/lay/ride/eject/hat/traces, личный профиль, свой ник и его оформление, личные сообщения и личный scoreboard. Старые ноды этих функций оставлены в plugin.yml для совместимости, но не закрывают базовые команды. Для ограничений используйте [правила модулей](MODULE_ACCESS_AND_GUI.md). `/setspawn`, управление порталами/голограммами, очередь жалоб и редакторы требуют административных прав.

Значения по умолчанию взяты из `plugin.yml`. Право не включает отключённый модуль и не обходит авторизацию LoveAuth. При работе с меню права проверяются повторно при действии.

| Право | По умолчанию | Доступ |
| --- | --- | --- |
| `loveessential.heal` | OP | Лечение себя |
| `loveessential.display.own` | Все | Устаревшее право совместимости. `/display` управляет своими подписями без отдельного разрешения, даже если этот узел запрещён. |
| `loveessential.feed` | OP | Восстановление сытости |
| `loveessential.fly` | OP | Разрешение полёта |
| `loveessential.god` | OP | Режим бога (`/god`) |
| `loveessential.speed` | OP | Скорость ходьбы/полёта |
| `loveessential.gamemode` | OP | Свой режим игры |
| `loveessential.repair` | OP | Ремонт предмета в руке |
| `loveessential.worlds.admin` | OP | Создание, импорт, загрузка/выгрузка и изменение миров |
| `loveessential.worlds.teleport` | Все | Список миров и переход; дополнительно проверяется право конкретного мира |
| `loveessential.scenarios.admin` | OP | Создание, изменение, запуск и сброс своего журнала сценариев |
| `loveessential.calamities.admin` | OP | Запуск и остановка бедствий, угроза, болезни игроков, расчёт и журнал (`/calamity`) |
| `loveessential.dialogs.admin` | OP | Создание и изменение нативных диалогов |
| `loveessential.content.edit` | OP | GUI-редакторы кастомных крафтов и стартового набора; входит в loveessential.admin |
| `loveessential.nick.others` | OP | Изменение ника другого онлайн-игрока |
| `loveessential.profile.location` | OP | Карточка координат/территории в разрешённой активности другого игрока; не обходит скрытие профиля или активности |
| `loveessential.invsee` | OP | Просмотр чужого инвентаря |
| `loveessential.invsee.edit` | OP | Редактирование чужого инвентаря; дополнительно нужен loveessential.invsee |
| `loveessential.admin` | OP | Главная панель /le, общее управление, reload/backup, настройка модулей и административные функции; включает дочерние права из plugin.yml |
| `loveessential.npc.view` | OP | Просмотр списка и выбор NPC |
| `loveessential.npc.create` | OP | Создание и клонирование NPC; работа мастера требует также npc.edit |
| `loveessential.npc.edit` | OP | Мастер, параметры и черновики NPC |
| `loveessential.npc.publish` | OP | Публикация и включение/отключение NPC |
| `loveessential.npc.delete` | OP | Удаление NPC с подтверждением |
| `loveessential.npc.path` | OP | Редактирование пути в активном черновике; требуется также npc.edit |
| `loveessential.npc.dialog` | OP | Редактирование диалога в активном черновике; требуется также npc.edit |
| `loveessential.npc.debug` | OP | Диагностика NPC |
| `loveessential.vanish` | OP | Переключение невидимости |
| `loveessential.vanish.see` | OP | Видеть игроков, скрытых модулем vanish |
| `loveessential.vanish.gui` | OP | Меню `/vanish` со списком игроков и прыжком к ним |
| `loveessential.revive.downable` | Все | Смертельный удар роняет игрока, а не убивает |
| `loveessential.revive.reviver` | Все | Поднимать раненых |
| `loveessential.revive.picker` | Все | Брать раненого на руки |
| `loveessential.revive.pickable` | Все | Позволять брать себя на руки |
| `loveessential.revive.robber` | Все | Обыскивать раненых |
| `loveessential.revive.stealable` | Все | Позволять обыскивать себя, когда ранен |
| `loveessential.revive.free` | OP | Поднимать без платы |
| `loveessential.revive.speed.<ранг>` | — | Множитель скорости подъёма из `revive.ranks` |
| `loveessential.revive.command.suicide` | Все | `/revive suicide` |
| `loveessential.revive.command.revive`, `.down`, `.kill` | OP | Административные подкоманды `/revive` |
| `loveessential.rtp` | Все | `/rtp` — случайный перенос |
| `loveessential.rtp.cooldown.<группа>` | — | Кулдаун из `rtp.cooldown-groups`; берётся наименьший из доступных и не больше `rtp.cooldown-seconds` |
| `loveessential.rtp.bypass.cooldown` | OP | Перенос без кулдауна |
| `loveessential.afk.bypass` | OP | Исключение из автоматического AFK-кика |
| `loveessential.npc.import` | OP | Импорт NPC; последующая работа черновика требует npc.edit |
| `loveessential.npc.export` | OP | Экспорт NPC |

## Пример выдачи через LuckPerms

```text
/lp group moderator permission set loveessential.invsee true
/lp group administrator permission set loveessential.invsee.edit true
/lp group administrator permission set loveessential.admin true
```

Для редактирования инвентаря выдайте обе ноды: `loveessential.invsee` и `loveessential.invsee.edit`. Сам LoveEssential не требует LuckPerms: права можно выдавать любым совместимым менеджером.

## Права групп TAB и карточек профиля

Группа TAB совпадает с правом `group.<id>` (например `group.admin`, которое выдаёт LuckPerms). Поле `permission` у группы в `modules/tab/tab.yml` заменяет это имя, если задано. Список игроков по мирам обходит право `loveessential.tab.worldlist.bypass`, задаваемое в `per-world-playerlist.bypass-permission`. Эти имена настраиваемые и не являются отдельными зарегистрированными нодами `plugin.yml`, кроме `loveessential.tab.worldlist.bypass`, которое проверяется по имени.

Редактор MOTD, редактор TAB и редактор табло (`/le scoreboard edit`, `/le scoreboard show ... <игрок>`) требуют `loveessential.admin`. Все новые административные права входят в него. Диалоги и миры могут дополнительно требовать собственное настроенное право.

`loveessential.waystones.admin` — создание, изменение и удаление путевых столбов
(по умолчанию OP). Обычное использование ближайшего столба не требует права.
Право маршрута, если задано, проверяется отдельно перед подготовкой и переносом.
