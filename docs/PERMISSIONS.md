# Права доступа

[Главная](../README.md) · [Команды](COMMANDS.md) · [Плейсхолдеры](PLACEHOLDERS.md)

Значения по умолчанию взяты из `plugin.yml`. Право не включает отключённый модуль и не обходит авторизацию LoveAuth. При работе с меню права проверяются повторно при действии.

| Право | По умолчанию | Доступ |
| --- | --- | --- |
| `loveessential.nick` | Все игроки | Изменение своего никнейма и сброс |
| `loveessential.nick.color` | Все игроки | Выбор одноцветного оформления |
| `loveessential.nick.gradient` | Все игроки | Выбор градиента |
| `loveessential.nick.others` | OP | Изменение ника другого онлайн-игрока; также нужны базовое право и право выбранного оформления |
| `loveessential.messages` | Все игроки | Личные сообщения, ответы, игнор и настройки |
| `loveessential.profile` | Все игроки | Просмотр досье |
| `loveessential.profile.location` | OP | Просмотр карточки координат/территории другого игрока; свою карточку владелец видит без этого права |
| `loveessential.invsee` | OP | Просмотр чужого инвентаря |
| `loveessential.invsee.edit` | OP | Редактирование чужого инвентаря; дополнительно нужен loveessential.invsee |
| `loveessential.admin` | OP | Общее управление, reload/backup, настройка модулей и административные функции; включает дочерние права из plugin.yml |
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
| `loveessential.ride` | OP | Посадка на сущность |
| `loveessential.ride.accept` | Не выдано | Разрешить посадку другого игрока на себя |
| `loveessential.afk.bypass` | OP | Исключение из автоматического AFK-кика |
| `loveessential.npc.import` | OP | Импорт NPC; последующая работа черновика требует npc.edit |
| `loveessential.npc.export` | OP | Экспорт NPC |

## Лимиты домов

Дополнительно проверяются `loveessential.home.limit.5`, `loveessential.home.limit.10`, `loveessential.home.limit.25`, `loveessential.home.limit.100`. Они задают лимит для игрока; при нескольких выданных правах используется наибольшее из этих значений. Базовый лимит задаётся через `default-limit` в `modules/home.yml`.

## Пример выдачи через LuckPerms

```text
/lp group moderator permission set loveessential.invsee true
/lp group administrator permission set loveessential.invsee.edit true
/lp user Alice permission set loveessential.home.limit.10 true
```

Для редактирования инвентаря выдайте обе ноды: `loveessential.invsee` и `loveessential.invsee.edit`. Сам LoveEssential не требует LuckPerms: права можно выдавать любым совместимым менеджером.

## Права групп TAB и карточек профиля

В `modules/tab.yml` поля `permission` задают доступ к группам; в стандартной конфигурации это `loveessential.group.admin` и `loveessential.group.knight`. В `modules/profile.yml` карточка также может содержать своё поле `permission`. Эти имена настраиваемые и не являются отдельными зарегистрированными нодами `plugin.yml`.
