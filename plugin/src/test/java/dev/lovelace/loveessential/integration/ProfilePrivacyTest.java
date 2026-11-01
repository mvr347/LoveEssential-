package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.module.*;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ProfilePrivacyTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  PlayerProfiles profiles;
  SqliteStore store;
  PlaceholderAPIPlugin papi;

  @BeforeEach
  void setup() throws Exception {
    server =
        MockBukkit.mock(
            new ServerMock() {
              @Override
              public String getBukkitVersion() {
                return "26.2-R0.1-SNAPSHOT";
              }
            });
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    await(plugin.module("tab", false));
    await(plugin.module("scoreboard", false));
    await(plugin.module("starter", false));
    profiles = internal("profiles", PlayerProfiles.class);
    store = internal("store", SqliteStore.class);
  }

  @AfterEach
  void close() throws Exception {
    // Exercise the real PAPI parser and expansion registry without its metrics/cloud lifecycle.
    if (papi != null) {
      var enabled = JavaPlugin.class.getDeclaredField("isEnabled");
      enabled.setAccessible(true);
      enabled.setBoolean(papi, false);
      var instance = PlaceholderAPIPlugin.class.getDeclaredField("instance");
      instance.setAccessible(true);
      instance.set(null, null);
    }
    MockBukkit.unmock();
  }

  <T> T internal(String name, Class<T> type) throws Exception {
    var f = LoveEssentialPlugin.class.getDeclaredField(name);
    f.setAccessible(true);
    return type.cast(f.get(plugin));
  }

  void waitFor(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean());
  }

  <T> T await(CompletableFuture<T> f) throws Exception {
    waitFor(f::isDone);
    return f.get(1, TimeUnit.SECONDS);
  }

  PlayerMock player(String name) throws Exception {
    var p = server.addPlayer(name);
    waitFor(() -> profiles.privacy().loaded(p.getUniqueId()));
    await(store.profile(p.getUniqueId(), "test.ready", "true"));
    server.getScheduler().performTicks(2);
    return p;
  }

  void command(PlayerMock p, String command) {
    assertTrue(server.dispatchCommand(p, command));
  }

  void click(PlayerMock p, int slot) {
    click(p, slot, ClickType.LEFT);
  }

  void click(PlayerMock p, int slot, ClickType type) {
    var e =
        new InventoryClickEvent(
            p.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            slot,
            type,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(e);
    assertTrue(e.isCancelled());
    server.getScheduler().performOneTick();
  }

  int named(PlayerMock p, String text) {
    var inventory = p.getOpenInventory().getTopInventory();
    for (int slot = 0; slot < inventory.getSize(); slot++)
      if (inventory.getItem(slot) != null && title(p, slot).contains(text)) return slot;
    fail("Missing button: " + text);
    return -1;
  }

  String lore(PlayerMock p) {
    return Arrays.stream(p.getOpenInventory().getTopInventory().getContents())
        .filter(Objects::nonNull)
        .map(item -> String.valueOf(item.getItemMeta().getLore()))
        .collect(java.util.stream.Collectors.joining());
  }

  /** Every item name of the open menu, one per line. */
  String titles(PlayerMock p) {
    var inventory = p.getOpenInventory().getTopInventory();
    var out = new StringBuilder();
    for (int slot = 0; slot < inventory.getSize(); slot++)
      if (inventory.getItem(slot) != null && inventory.getItem(slot).getItemMeta().hasDisplayName())
        out.append(title(p, slot)).append('\n');
    return out.toString();
  }

  String title(PlayerMock p, int slot) {
    return PlainTextComponentSerializer.plainText()
        .serialize(
            p.getOpenInventory().getTopInventory().getItem(slot).getItemMeta().displayName());
  }

  /** The lore of one slot of the open menu as plain text, one line per row. */
  String slotLore(PlayerMock p, int slot) {
    var lore = p.getOpenInventory().getTopInventory().getItem(slot).getItemMeta().lore();
    return lore == null
        ? ""
        : lore.stream()
            .map(line -> PlainTextComponentSerializer.plainText().serialize(line))
            .collect(java.util.stream.Collectors.joining("\n"));
  }

  void enablePapi() throws Exception {
    MockBukkit.createMockPlugin("PlaceholderAPI");
    var unsafeType = Class.forName("sun.misc.Unsafe");
    var field = unsafeType.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    papi =
        (PlaceholderAPIPlugin)
            unsafeType
                .getMethod("allocateInstance", Class.class)
                .invoke(field.get(null), PlaceholderAPIPlugin.class);
    for (var item :
        Map.of(
                "server",
                server,
                "dataFolder",
                new java.io.File(plugin.getDataFolder(), "papi-fixture"),
                "description",
                new PluginDescriptionFile(
                    "PlaceholderAPI", "2.11.6", PlaceholderAPIPlugin.class.getName()),
                "logger",
                java.util.logging.Logger.getLogger("PAPI-fixture"))
            .entrySet()) {
      var member = JavaPlugin.class.getDeclaredField(item.getKey());
      member.setAccessible(true);
      member.set(papi, item.getValue());
    }
    var manager = new me.clip.placeholderapi.expansion.manager.LocalExpansionManager(papi);
    var managerField = PlaceholderAPIPlugin.class.getDeclaredField("localExpansionManager");
    managerField.setAccessible(true);
    managerField.set(papi, manager);
    var expansionsField = manager.getClass().getDeclaredField("expansions");
    expansionsField.setAccessible(true);
    @SuppressWarnings("unchecked")
    var expansions = (Map<String, PlaceholderExpansion>) expansionsField.get(manager);
    var enabled = JavaPlugin.class.getDeclaredField("isEnabled");
    enabled.setAccessible(true);
    enabled.setBoolean(papi, true);
    var instance = PlaceholderAPIPlugin.class.getDeclaredField("instance");
    instance.setAccessible(true);
    instance.set(null, papi);
    for (String id :
        List.of(
            "loveadaptation",
            "lovebehavior",
            "lovesubnames",
            "clans",
            "lovetrades",
            "lovechat",
            "lovechatfilter")) {
      expansions.put(
          id,
          new PlaceholderExpansion() {
            public String getIdentifier() {
              return id;
            }

            public String getAuthor() {
              return "test";
            }

            public String getVersion() {
              return "1";
            }

            @Override
            public String onPlaceholderRequest(Player target, String params) {
              if (id.equals("clans") || id.equals("lovetrades")) return "yes";
              if (id.equals("lovechat")) return params.equals("channel") ? "global" : "false";
              if (id.equals("lovechatfilter")) return "false";
              return "&a" + target.getName() + ":" + params;
            }
          });
    }
  }

  public static class Neighbor extends JavaPlugin {
    public Neighbor() {}
  }

  final List<String> calls = new ArrayList<>();

  Neighbor neighbor(String name, String... commands) throws Exception {
    var description =
        new PluginDescriptionFile(
            new java.io.StringReader(
                "name: "
                    + name
                    + "\nversion: 1\nmain: "
                    + Neighbor.class.getName()
                    + "\ncommands:\n"
                    + Arrays.stream(commands)
                        .map(c -> "  " + c + ": {}\n")
                        .collect(java.util.stream.Collectors.joining())));
    var n = MockBukkit.loadWith(Neighbor.class, description);
    for (String c : commands)
      n.getCommand(c)
          .setExecutor(
              (sender, cmd, label, args) -> {
                calls.add(
                    n.getName()
                        + ":"
                        + cmd.getName()
                        + ":"
                        + sender.getName()
                        + ":"
                        + String.join(" ", args));
                return true;
              });
    return n;
  }

  @Test
  void removedCommandsAndAliasesAreNotRegistered() {
    for (String name : List.of("workbench", "enderchest", "craft", "ec"))
      assertNull(plugin.getCommand(name));
    var p = server.addPlayer("Visitor");
    var root = server.getCommandMap().tabComplete(p, "le ");
    assertFalse(root.contains("workbench"));
    assertFalse(root.contains("enderchest"));
    assertNotNull(plugin.getCommand("crafts"));
  }

  @Test
  void settingsUseReferenceHeadsAndRussianStatesWithoutChangingRawPlaceholders() throws Exception {
    enablePapi();
    var p = player("Owner");
    command(p, "profile settings");
    var inventory = p.getOpenInventory().getTopInventory();
    assertEquals(54, inventory.getSize());
    // One filter button in the header, three rows of switches below it.
    assertTrue(title(p, 4).contains("Фильтр"), title(p, 4));
    // The state of a switch is in its title, so titles count as well as lore.
    String text = lore(p) + titles(p);
    for (int i = 0; i < 4; i++) {
      click(p, 4);
      text += lore(p) + titles(p);
    }
    assertTrue(text.contains("Да") || text.contains("Включено"), text);
    assertTrue(text.contains("Выключено"));
    assertTrue(text.contains("Глобальный"));
    // (The mocked PlaceholderAPI answers "yes" to the clan rank in the head too.)
    assertFalse(text.replace("Ранг: §eyes", "").contains("yes"));
    assertFalse(text.contains("false"));
    assertEquals(
        "yes", internal("placeholders", Placeholders.class).papi(p, p, "%clans_invites_enabled%"));
    command(p, "profile");
    var meta =
        (org.bukkit.inventory.meta.SkullMeta)
            p.getOpenInventory().getTopInventory().getItem(named(p, "Настройки")).getItemMeta();
    // MockBukkit does not decode texture properties into URLs. Assert the exact client payload.
    String expected = plugin.getConfig().getString("profile.gui.textures.profile-menu.settings");
    assertTrue(
        meta.getPlayerProfile().getProperties().stream()
            .anyMatch(
                property ->
                    property.getName().equals("textures") && property.getValue().equals(expected)),
        "expected="
            + expected
            + " actual="
            + meta.getPlayerProfile().getProperties()
            + " cfg="
            + plugin.getConfig().getConfigurationSection("profile.gui").getKeys(true));
  }

  @Test
  void statisticsAreRealAndHaveTheirOwnMenu() throws Exception {
    var viewer = player("Viewer");
    var target = player("Target");
    target.setStatistic(Statistic.PLAYER_KILLS, 9);
    target.setStatistic(Statistic.DEATHS, 3);
    target.setStatistic(Statistic.PLAY_ONE_MINUTE, 72000);
    target.setStatistic(Statistic.MINE_BLOCK, Material.DIAMOND_ORE, 17);
    command(viewer, "profile Target");
    assertTrue(lore(viewer).contains("Общая статистика"));
    assertFalse(lore(viewer).contains("K/D:"));
    assertEquals(45, viewer.getOpenInventory().getTopInventory().getSize());
    click(viewer, named(viewer, "Мир")); // public summary has no drill-down
    assertEquals(45, viewer.getOpenInventory().getTopInventory().getSize());
    command(target, "profile");
    assertEquals(27, target.getOpenInventory().getTopInventory().getSize());
    assertEquals("Настройки", title(target, named(target, "Настройки")));
    assertEquals("Статистика", title(target, named(target, "Статистика")));
    assertFalse(titles(target).contains("Активность"), titles(target));
    assertFalse(lore(target).contains("обновить"), lore(target));
    click(target, named(target, "Статистика"));
    assertEquals(54, target.getOpenInventory().getTopInventory().getSize());
    // One button per category; the numbers are in the lore of its current section.
    int world = named(target, "Мир"), combat = named(target, "Сражения");
    String general = slotLore(target, world);
    assertTrue(general.contains("Раздел: Общее"), general);
    assertTrue(general.contains("В игре: 1 ч 0 мин"), general);
    String fights = slotLore(target, combat);
    assertTrue(fights.contains("Убийств игроков: 9"), fights);
    assertTrue(fights.contains("K/D: 3.0"), fights);
    // The block counters are on the third section of the world button.
    click(target, world);
    click(target, world);
    String building = slotLore(target, world);
    assertTrue(building.contains("Раздел: Строительство"), building);
    assertTrue(building.contains("Блоков добыто: 17"), building);
    // Back is the second to last slot of the menu.
    click(target, 52);
    assertEquals(27, target.getOpenInventory().getTopInventory().getSize());
  }

  @Test
  void statisticButtonsLeafThroughTheirSectionsInACircle() throws Exception {
    for (String name :
        List.of(
            "LoveClans", "LoveActivities", "LoveContracts", "LoveBrewing", "LoveDuels", "LoveHunt",
            "LoveTrades", "LoveShops")) MockBukkit.createMockPlugin(name);
    var p = player("Reader");
    command(p, "profile stats");
    var inventory = p.getOpenInventory().getTopInventory();
    assertEquals(54, inventory.getSize());
    // Five buttons sit like the dots on a die in the 3x3 block; no head, back and close in the last row.
    assertEquals(
        List.of("Мир", "Кланы", "Активности", "Сражения", "Торговля"),
        List.of(14, 16, 24, 32, 34).stream()
            .map(slot -> title(p, slot))
            .toList());
    // Opened straight from the command the menu is a root: no Back (it appears when opened from the profile).
    assertNull(inventory.getItem(52));
    assertTrue(title(p, 53).contains("Закрыть"), title(p, 53));
    for (int slot : List.of(15, 23, 25, 33)) assertNull(inventory.getItem(slot), "slot " + slot);
    // Frameless: the drawn background shows, so there is no glass anywhere.
    for (int slot = 0; slot < inventory.getSize(); slot++)
      assertTrue(
          inventory.getItem(slot) == null || !inventory.getItem(slot).getType().name().endsWith("GLASS_PANE"),
          "glass at " + slot);

    String first = slotLore(p, 14);
    assertTrue(first.contains("Раздел: Общее"), first);
    assertFalse(first.contains("›"), first);
    assertFalse(first.contains("(1/"), first);
    click(p, 14);
    assertTrue(slotLore(p, 14).contains("Раздел: Передвижение"), slotLore(p, 14));
    click(p, 14);
    click(p, 14);
    assertTrue(slotLore(p, 14).contains("Раздел: Выживание"), slotLore(p, 14));
    click(p, 14);
    assertEquals(first, slotLore(p, 14), "after the last section comes the first");
    click(p, 14, ClickType.RIGHT);
    assertTrue(slotLore(p, 14).contains("Раздел: Выживание"), slotLore(p, 14));
    // Buttons leaf independently of each other.
    assertTrue(slotLore(p, 34).contains("Раздел: Общее"), slotLore(p, 34));
    command(p, "profile stats");
    assertEquals(first, slotLore(p, 14), "closing the menu forgets the position");
  }

  @Test
  void aCategoryOfAnAbsentPluginAndItsSectionsAreLeftOut() throws Exception {
    var p = player("Reader");
    command(p, "profile stats");
    String titles = titles(p);
    assertFalse(titles.contains("Кланы"), titles);
    assertFalse(titles.contains("Активности"), titles);
    // Fights and trading keep the sections that need no other plugin.
    assertTrue(slotLore(p, named(p, "Сражения")).contains("Раздел: Общее"));
    assertFalse(slotLore(p, named(p, "Сражения")).contains("Дуэли"));
  }

  @Test
  void offlineProfileLoadsSqlSnapshotSkinAndPublicSummary() throws Exception {
    var viewer = player("Viewer");
    var target = player("Target");
    String url = "https://textures.minecraft.net/texture/" + "a".repeat(64);
    var profile = target.getPlayerProfile();
    var textures = profile.getTextures();
    textures.setSkin(java.net.URI.create(url).toURL());
    profile.setTextures(textures);
    target.setPlayerProfile(profile);
    target.setStatistic(Statistic.PLAY_ONE_MINUTE, 72000);
    command(target, "profile");
    target.disconnect();
    profiles.openProfile(viewer, "target");
    waitFor(
        () ->
            viewer.getOpenInventory().getTopInventory() != null
                && viewer.getOpenInventory().getTopInventory().getSize() == 45);
    assertTrue(lore(viewer).contains("В игре: 1 ч 0 мин"));
    var meta =
        (org.bukkit.inventory.meta.SkullMeta)
            viewer.getOpenInventory().getTopInventory().getItem(0).getItemMeta();
    assertEquals(url, meta.getPlayerProfile().getTextures().getSkin().toExternalForm());
    assertNotEquals(target.getUniqueId(), meta.getPlayerProfile().getId());
    assertTrue(
        meta.getPlayerProfile().getProperties().stream()
            .anyMatch(property -> property.getName().equals("textures")));
    assertFalse(lore(viewer).contains("K/D:"));
    assertTrue(viewer.getOpenInventory().getTopInventory().getItem(named(viewer, "Пожаловаться")) != null);
    assertFalse(
        title(viewer, 43).contains("Назад"), "direct offline profile has no invented parent");
  }

  @Test
  void offlineProfileWithoutUsableSkinUsesOwnerlessFallback() throws Exception {
    var viewer = player("Viewer");
    var id = UUID.fromString("276d13c9-b8b6-3421-9c33-4960f44bf2d4");
    for (String skin : List.of("", "broken", "https://example.com/texture/" + "a".repeat(64))) {
      await(
          store.profile(
              id,
              "loveessential.player-info",
              new com.google.gson.Gson().toJson(Map.of("player", "Ada123", "skin_url", skin))));
      for (int i = 0; i < 2; i++) {
        viewer.closeInventory();
        profiles.openProfile(viewer, "Ada123");
        waitFor(
            () ->
                viewer.getOpenInventory().getTopInventory() != null
                    && viewer.getOpenInventory().getTopInventory().getSize() == 45);
        var meta =
            (org.bukkit.inventory.meta.SkullMeta)
                viewer.getOpenInventory().getTopInventory().getItem(0).getItemMeta();
        assertNull(meta.getPlayerProfile(), "saved skin: " + skin);
      }
    }
  }

  @Test
  void offlineProfileDoesNotBypassPrivacyOrLeakHiddenActivity() throws Exception {
    var viewer = player("Viewer");
    viewer.setOp(true);
    var target = player("Target");
    await(profiles.privacy().change(target, old -> new ProfilePrivacy.Preferences(true, false)));
    target.disconnect();
    profiles.openProfile(viewer, "Target");
    waitFor(
        () ->
            viewer.getOpenInventory().getTopInventory() != null
                && viewer.getOpenInventory().getTopInventory().getSize() == 45);
    var menu = viewer.getOpenInventory().getTopInventory();
    click(viewer, named(viewer, "Мир"));
    assertSame(menu, viewer.getOpenInventory().getTopInventory());
    target.reconnect();
    waitFor(() -> profiles.privacy().loaded(target.getUniqueId()));
    await(profiles.privacy().change(target, old -> new ProfilePrivacy.Preferences(false, false)));
    target.disconnect();
    viewer.closeInventory();
    profiles.openProfile(viewer, "Target");
    var requests = PlayerProfiles.class.getDeclaredField("requests");
    requests.setAccessible(true);
    var pending = (Map<?, ?>) requests.get(profiles);
    waitFor(() -> !pending.containsKey(viewer.getUniqueId()));
    assertNull(viewer.getOpenInventory().getTopInventory());
  }

  @Test
  void unknownPreferencesNeverExposeProfileToAnotherPlayer() throws Exception {
    var a = player("Viewer");
    a.setOp(true);
    var b = player("Target");
    var statesField = ProfilePrivacy.class.getDeclaredField("states");
    statesField.setAccessible(true);
    ((Map<?, ?>) statesField.get(profiles.privacy())).remove(b.getUniqueId());
    assertFalse(profiles.privacy().loaded(b.getUniqueId()));
    assertFalse(profiles.canView(a, b));
    assertTrue(profiles.canView(b, b));
  }

  @Test
  void publicSummaryUsesTargetSnapshotAndCurrentSkin() throws Exception {
    enablePapi();
    var viewer = player("Viewer");
    var target = player("Target");
    target.setStatistic(Statistic.PLAY_ONE_MINUTE, 72000);
    var skin = target.getPlayerProfile();
    var textures = skin.getTextures();
    textures.setSkin(
        java.net.URI.create("https://textures.minecraft.net/texture/" + "b".repeat(64)).toURL());
    skin.setTextures(textures);
    target.setPlayerProfile(skin);
    command(viewer, "le profile view Target");
    assertEquals(45, viewer.getOpenInventory().getTopInventory().getSize());
    assertTrue(lore(viewer).contains("В игре: 1 ч 0 мин"));
    assertFalse(lore(viewer).contains("Координаты:"));
    var skull =
        (org.bukkit.inventory.meta.SkullMeta)
            viewer.getOpenInventory().getTopInventory().getItem(0).getItemMeta();
    assertEquals(textures.getSkin(), skull.getPlayerProfile().getTextures().getSkin());
    click(viewer, named(viewer, "Мир"));
    assertEquals(45, viewer.getOpenInventory().getTopInventory().getSize());
  }

  @Test
  void hideClosesExistingViewAndHidesListCompletionEvenForOp() throws Exception {
    var owner = player("Owner");
    var viewer = player("Viewer");
    viewer.setOp(true);
    command(viewer, "profile Owner");
    assertEquals(45, viewer.getOpenInventory().getTopInventory().getSize());
    command(owner, "profile hide");
    waitFor(() -> !profiles.privacy().preferences(owner).profileVisible());
    assertFalse(
        viewer.getOpenInventory().getTopInventory() != null
            && viewer.getOpenInventory().getTopInventory().getSize() == 45);
    command(viewer, "profile Owner");
    assertFalse(
        viewer.getOpenInventory().getTopInventory() != null
            && viewer.getOpenInventory().getTopInventory().getSize() == 45);
    assertFalse(server.getCommandMap().tabComplete(viewer, "le profile view ").contains("Owner"));
    command(owner, "profile");
    assertEquals(27, owner.getOpenInventory().getTopInventory().getSize());
    command(viewer, "profile list");
    assertFalse(lore(viewer).contains("Owner"));
    command(owner, "profile show");
    waitFor(() -> profiles.privacy().preferences(owner).profileVisible());
    command(viewer, "profile view Owner");
    assertEquals(45, viewer.getOpenInventory().getTopInventory().getSize());
  }

  @Test
  void activityCanBeHiddenWithoutHidingOwnAdaptations() throws Exception {
    neighbor("LoveAdaptation", "loveadaptation");
    enablePapi();
    var owner = player("Owner");
    var viewer = player("Viewer");
    owner.setStatistic(Statistic.PLAY_ONE_MINUTE, 20 * 60 * 60 * 77);
    command(viewer, "profile Owner");
    assertTrue(lore(viewer).contains("77"));
    command(owner, "profile activity hide");
    waitFor(() -> !profiles.privacy().preferences(owner).activityVisible());
    command(viewer, "profile Owner");
    assertTrue(profiles.canView(viewer, owner));
    assertFalse(lore(viewer).contains("77"));
    owner.addAttachment(plugin, "loveadaptation.use", true);
    command(owner, "profile");
    click(owner, named(owner, "Адаптации"));
    assertEquals("LoveAdaptation:loveadaptation:Owner:mainmenu", calls.getLast());
    command(owner, "profile settings");
    assertTrue(title(owner, named(owner, "Профиль доступен")).contains("доступен"));
    assertTrue(title(owner, named(owner, "Активность скрыта")).contains("скрыта"));
    click(owner, named(owner, "Активность скрыта"));
    waitFor(() -> profiles.privacy().preferences(owner).activityVisible());
    assertTrue(profiles.privacy().preferences(owner).profileVisible());
  }

  @Test
  void guiHasIndependentPersistentPrivacyTogglesAndNoReputationOnSelf() throws Exception {
    var owner = player("Owner");
    command(owner, "profile settings");
    click(owner, named(owner, "Профиль доступен"));
    waitFor(() -> !profiles.privacy().preferences(owner).profileVisible());
    assertTrue(profiles.privacy().preferences(owner).activityVisible());
    var doc =
        await(
                store.get(
                    "profile-preferences",
                    owner.getUniqueId().toString(),
                    ProfilePrivacy.Preferences.class))
            .orElseThrow();
    assertFalse(doc.value().profileVisible());
    assertTrue(doc.value().activityVisible());
    command(owner, "profile");
    assertFalse(titles(owner).contains("Репутация"), "no reputation card on the own profile");
    assertTrue(calls.isEmpty());
  }

  @Test
  void placeholdersAreAsyncSafeAndProduceUsableMenuCommands() throws Exception {
    var p = player("Owner");
    command(p, "profile hide");
    waitFor(() -> !profiles.privacy().preferences(p).profileVisible());
    var placeholders = internal("placeholders", Placeholders.class);
    var expansion =
        new LoveExpansion(
            placeholders,
            internal("lang", dev.lovelace.loveessential.lang.Lang.class),
            internal("love", LoveBridge.class),
            v -> false,
            v -> false);
    assertEquals(
        "false",
        CompletableFuture.supplyAsync(() -> expansion.onRequest(p, "profile_visible"))
            .get(1, TimeUnit.SECONDS));
    assertEquals("true", expansion.onRequest(p, "profile_hidden"));
    assertEquals("true", expansion.onRequest(p, "activity_enabled"));
    assertEquals("false", expansion.onRequest(p, "activity_visible"));
    assertEquals("loveessential:profile view Owner", expansion.onRequest(p, "profile_command"));
    command(p, expansion.onRequest(p, "profile_toggle_command"));
    waitFor(() -> profiles.privacy().preferences(p).profileVisible());
  }

  @Test
  void pendingPrivacyWriteIsRespectedAfterFastReconnect() throws Exception {
    var p = player("Owner");
    var saving = profiles.privacy().change(p, old -> new ProfilePrivacy.Preferences(false, false));
    p.disconnect();
    p.reconnect();
    await(saving);
    waitFor(() -> profiles.privacy().loaded(p.getUniqueId()));
    assertFalse(profiles.privacy().preferences(p).profileVisible());
    assertFalse(profiles.privacy().preferences(p).activityVisible());
  }

  @Test
  void repButtonUsesTargetNameWhileOwnerUsesBehavior() throws Exception {
    neighbor("LoveBehavior", "rep", "behavior");
    var viewer = player("Viewer");
    var target = player("Target");
    viewer.addAttachment(plugin, "lovebehavior.rep", true);
    viewer.addAttachment(plugin, "lovebehavior.behavior", true);
    command(viewer, "profile Target");
    click(viewer, named(viewer, "Репутация"));
    assertEquals(List.of("LoveBehavior:rep:Viewer:Target"), calls);
    command(viewer, "profile");
    click(viewer, named(viewer, "Поведение"));
    // Opened from the profile the plugin gets its "came from profile" argument.
    assertEquals("LoveBehavior:behavior:Viewer:mainmenu", calls.getLast());
  }

  @Test
  void privateAccountCannotBeOpenedFromAnotherPlayersProfile() throws Exception {
    neighbor("LoveBehavior", "account");
    plugin.getConfig().set("profile.links.account.plugin", "LoveBehavior");
    plugin.getConfig().set("profile.links.account.command", "account");
    var viewer = player("Viewer");
    var target = player("Target");
    command(viewer, "profile Target");
    assertFalse(titles(viewer).contains("Безопасность"), "no account card on another profile");
    assertTrue(calls.isEmpty());
    command(viewer, "profile");
    click(viewer, named(viewer, "Безопасность"));
    assertEquals(List.of("LoveBehavior:account:Viewer:profile"), calls);
  }

  @Test
  void notificationButtonsUseLoveCoreAndKeepOneChannelEnabled() throws Exception {
    var channels = EnumSet.of(dev.lovelace.lovecore.api.notify.LoveNotify.Channel.CHAT);
    var notify =
        new dev.lovelace.lovecore.api.notify.LoveNotify() {
          public Set<Channel> getEnabledChannels(UUID id) {
            return Set.copyOf(channels);
          }

          public boolean isChannelEnabled(UUID id, Channel c) {
            return channels.contains(c);
          }

          public boolean toggleChannel(UUID id, Channel c) {
            setChannelEnabled(id, c, !channels.contains(c));
            return channels.contains(c);
          }

          public boolean setChannelEnabled(UUID id, Channel c, boolean on) {
            if (!on && channels.size() == 1 && channels.contains(c)) return false;
            if (on) channels.add(c);
            else channels.remove(c);
            return true;
          }

          public void notify(
              Player p,
              net.kyori.adventure.text.Component c,
              Set<Channel> selected,
              boolean important) {}

          public void notifyInteractive(Player p, net.kyori.adventure.text.Component c) {}
        };
    server
        .getServicesManager()
        .register(
            dev.lovelace.lovecore.api.notify.LoveNotify.class,
            notify,
            plugin,
            ServicePriority.Normal);
    var p = player("Owner");
    command(p, "profile settings");
    click(p, named(p, "Уведомления"));
    assertEquals(27, p.getOpenInventory().getTopInventory().getSize());
    click(p, named(p, "Канал: Чат"));
    assertEquals(1, channels.size());
    click(p, named(p, "Канал: Actionbar"));
    assertTrue(channels.contains(dev.lovelace.lovecore.api.notify.LoveNotify.Channel.ACTION_BAR));
    click(p, named(p, "Канал: Чат"));
    assertFalse(channels.contains(dev.lovelace.lovecore.api.notify.LoveNotify.Channel.CHAT));
    assertEquals(1, channels.size());
  }

  @Test
  void redirectedCommandCannotRunAsDifferentPluginOrEscalateSender() throws Exception {
    neighbor("LoveBehavior", "rep");
    var viewer = player("Viewer");
    var target = player("Target");
    viewer.addAttachment(plugin, "lovebehavior.rep", true);
    plugin.getConfig().set("profile.links.reputation.command", "loveessential:reload");
    command(viewer, "profile Target");
    click(viewer, 15);
    assertTrue(calls.isEmpty());
    assertFalse(viewer.isOp());
  }
}
