package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.lang.Lang;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;

/**
 * Every text key the code asks for must exist in the loaded language, otherwise a menu or message
 * shows the raw {@code [key]}. Keys are read from the call sites, so a new call with a typo fails here.
 */
class LangKeysTest {
  ServerMock server;
  LoveEssentialPlugin plugin;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    BooleanSupplier ready = plugin::ready;
    while (!ready.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(ready.getAsBoolean());
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  /** {@code lang.render("key"...)}, {@code lang.send(x, "key"...)} and the like, with a literal key. */
  static final Pattern CALL =
      Pattern.compile(
          "\\blang\\s*\\.\\s*(?:render|text|lines|contains|rich|renderLines)\\s*\\(\\s*\"([a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)+)\"");

  static final Pattern SEND =
      Pattern.compile("\\blang\\s*\\.\\s*send\\s*\\(\\s*[A-Za-z_.()]+\\s*,\\s*\"([a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)+)\"");

  static final Pattern THROWN =
      Pattern.compile("new IllegalArgumentException\\(\\s*\"([a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)+)\"");

  Lang lang() throws Exception {
    for (var field : LoveEssentialPlugin.class.getDeclaredFields())
      if (field.getType() == Lang.class) {
        field.setAccessible(true);
        return (Lang) field.get(plugin);
      }
    throw new IllegalStateException("no Lang");
  }

  /**
   * A lore line written as {@code - <gray>After the click:} is read by YAML as a map, and the menu
   * then shows {@code {After the click=null}}. Every list in the language must hold only strings.
   */
  @Test
  void listsHoldOnlyTextLines() throws Exception {
    var field = Lang.class.getDeclaredField("values");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    var values = (Map<String, Object>) field.get(lang());
    var broken = new TreeMap<String, String>();
    values.forEach(
        (key, value) -> {
          if (value instanceof List<?> list)
            for (Object line : list)
              if (!(line instanceof String || line instanceof Number || line instanceof Boolean)) {
                broken.put(key, String.valueOf(line));
                break;
              }
        });
    assertTrue(broken.isEmpty(), "Lists with a line that is not text (quote lines that end with a colon): " + broken);
  }

  /** Keys the code builds from an enum or a fixed set of words: every member needs its text. */
  @Test
  void keyFamiliesBuiltFromNamesExist() throws Exception {
    var lang = lang();
    var missing = new TreeSet<String>();
    java.util.function.BiConsumer<String, String> need =
        (prefix, name) -> {
          if (!lang.contains(prefix + name.toLowerCase(Locale.ROOT))) missing.add(prefix + name.toLowerCase(Locale.ROOT));
        };
    for (var e : dev.lovelace.lovecore.api.display.DisplayPrefs.Element.values()) need.accept("display.element-", e.name());
    for (var e : dev.lovelace.lovecore.api.display.DisplayPrefs.Surface.values()) need.accept("display.surface-", e.name());
    for (var e : dev.lovelace.lovecore.api.display.DisplayPrefs.Scope.values()) need.accept("display.scope-", e.name());
    for (String filter : List.of("all", "world", "near")) need.accept("vanish.filter-", filter);
    for (var mode : org.bukkit.GameMode.values()) need.accept("vanish.mode-", mode.name());
    for (var channel : dev.lovelace.lovecore.api.notify.LoveNotify.Channel.values())
      need.accept("profile-menu.notify-", channel.name().replace("_", ""));
    for (String action : List.of("profile", "invsee", "msg", "ignore")) need.accept("players.select-", action);
    assertTrue(missing.isEmpty(), "Key families with a missing member: " + missing);
  }

  @Test
  void everyLiteralKeyInTheCodeExistsInTheLanguage() throws Exception {
    Lang lang = null;
    for (var field : LoveEssentialPlugin.class.getDeclaredFields())
      if (field.getType() == Lang.class) {
        field.setAccessible(true);
        lang = (Lang) field.get(plugin);
      }
    assertNotNull(lang);
    var missing = new TreeMap<String, Set<String>>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        for (var pattern : List.of(CALL, SEND, THROWN)) {
          Matcher m = pattern.matcher(source);
          while (m.find()) {
            String key = m.group(1);
            // "display.element-" + name is a key family, checked by familiesExist().
            if (key.endsWith("-") || key.endsWith(".")) continue;
            if (!lang.contains(key))
              missing.computeIfAbsent(key, k -> new TreeSet<>()).add(file.getFileName().toString());
          }
        }
      }
    }
    assertTrue(missing.isEmpty(), "Keys used in the code but missing from the language: " + missing);
  }
}
