package dev.lovelace.loveessential.content;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;

/** Immutable SQL documents; Bukkit recipes are registered and removed only on the server thread. */
public final class ContentLibrary implements Listener, AutoCloseable {
  public record Craft(
      String id,
      boolean enabled,
      boolean shapeless,
      boolean exact,
      String permission,
      List<ContentItems> ingredients,
      ContentItems result) {
    public Craft {
      ingredients = Collections.unmodifiableList(new ArrayList<>(ingredients));
    }
  }

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final Map<String, SqliteStore.Document<Craft>> crafts = new ConcurrentHashMap<>();
  private final Set<NamespacedKey> registered = new HashSet<>();

  public ContentLibrary(
      LoveEssentialPlugin plugin, MainThread main, SqliteStore store, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public CompletableFuture<Void> load() {
    return store
        .list("custom-craft", Craft.class)
        .thenAccept(saved -> saved.forEach(d -> crafts.put(d.id(), d)));
  }

  public List<String> ids() {
    return crafts.keySet().stream().sorted().toList();
  }

  public Optional<SqliteStore.Document<Craft>> craft(String id) {
    return Optional.ofNullable(crafts.get(id));
  }

  public static void id(String id) {
    if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("validation.id");
  }

  public boolean allowed(Player p, Craft c) {
    return plugin.allows("crafting", p)
        && c.enabled()
        && love.authenticated(p)
        && (c.permission().isBlank() || p.hasPermission(c.permission()));
  }

  public CompletableFuture<Void> texts(Map<String, String> values) {
    var immutable = Map.copyOf(values);
    String publication = "content-" + UUID.randomUUID();
    return store
        .prepare(publication, immutable)
        .thenCompose(
            v ->
                CompletableFuture.runAsync(
                    () -> {
                      try {
                        lang.putAll(immutable);
                      } catch (Exception e) {
                        throw new CompletionException(e);
                      }
                    },
                    plugin.io()))
        .thenCompose(v -> store.complete(publication));
  }

  public CompletableFuture<Void> save(
      Craft value, long revision, String actor, Map<String, String> texts) {
    id(value.id());
    validate(value);
    if (revision == 0 && crafts.size() >= plugin.getConfig().getInt("crafting.max-recipes", 512))
      throw new IllegalArgumentException("content.limit");
    // Validate the native recipe before committing a definition that the server cannot register.
    nativeRecipe(value);
    return texts(texts)
        .thenCompose(v -> store.save("custom-craft", value.id(), revision, value, actor))
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      crafts.put(value.id(), new SqliteStore.Document<>(value.id(), rev, value));
                      refresh();
                      return null;
                    }));
  }

  public CompletableFuture<Void> delete(String id, long revision, String actor) {
    return store
        .delete("custom-craft", id, revision, actor)
        .thenCompose(
            v ->
                main.call(
                    () -> {
                      crafts.remove(id);
                      refresh();
                      return null;
                    }));
  }

  public static void validate(Craft c) {
    if (c.ingredients().size() != 9
        || c.result() == null
        || c.ingredients().stream().allMatch(Objects::isNull))
      throw new IllegalArgumentException("content.incomplete-recipe");
  }

  public NamespacedKey key(String id) {
    return new NamespacedKey(plugin, "craft_" + id);
  }

  private RecipeChoice choice(ContentItems data, boolean exact) {
    var item = data.item(lang);
    item.setAmount(1);
    return exact
        ? new RecipeChoice.ExactChoice(item)
        : new RecipeChoice.MaterialChoice(item.getType());
  }

  private Recipe nativeRecipe(Craft c) {
    validate(c);
    if (c.shapeless()) {
      var recipe = new ShapelessRecipe(key(c.id()), c.result().item(lang));
      c.ingredients().stream()
          .filter(Objects::nonNull)
          .forEach(i -> recipe.addIngredient(choice(i, c.exact())));
      return recipe;
    }
    var recipe = new ShapedRecipe(key(c.id()), c.result().item(lang));
    StringBuilder shape = new StringBuilder();
    for (int i = 0; i < 9; i++)
      shape.append(c.ingredients().get(i) == null ? ' ' : (char) ('a' + i));
    recipe.shape(shape.substring(0, 3), shape.substring(3, 6), shape.substring(6, 9));
    for (int i = 0; i < 9; i++)
      if (c.ingredients().get(i) != null)
        recipe.setIngredient((char) ('a' + i), choice(c.ingredients().get(i), c.exact()));
    return recipe;
  }

  public void refresh() {
    MainThread.require();
    for (var k : registered) {
      Bukkit.removeRecipe(k);
      for (var p : Bukkit.getOnlinePlayers()) p.undiscoverRecipe(k);
    }
    registered.clear();
    if (!plugin.module("crafting")) return;
    for (var d : crafts.values())
      if (d.value().enabled()) {
        if (!Bukkit.addRecipe(nativeRecipe(d.value())))
          throw new IllegalStateException("content.recipe-conflict");
        registered.add(key(d.id()));
      }
    Bukkit.getOnlinePlayers().forEach(this::discover);
  }

  private void discover(Player p) {
    for (var d : crafts.values())
      if (registered.contains(key(d.id())) && allowed(p, d.value())) p.discoverRecipe(key(d.id()));
  }

  private Craft ours(Recipe recipe) {
    if (!(recipe instanceof Keyed k)
        || !k.getKey().getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))
        || !k.getKey().getKey().startsWith("craft_")) return null;
    var d = crafts.get(k.getKey().getKey().substring(6));
    return d == null ? null : d.value();
  }

  private boolean custom(Recipe recipe) {
    return recipe instanceof Keyed k
        && k.getKey().getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))
        && k.getKey().getKey().startsWith("craft_");
  }

  @EventHandler
  public void prepare(PrepareItemCraftEvent e) {
    if (custom(e.getRecipe())
        && (!(e.getView().getPlayer() instanceof Player p)
            || ours(e.getRecipe()) == null
            || !allowed(p, ours(e.getRecipe())))) e.getInventory().setResult(null);
  }

  @EventHandler(ignoreCancelled = true)
  public void craft(CraftItemEvent e) {
    if (!custom(e.getRecipe())) return;
    var c = ours(e.getRecipe());
    if (!(e.getWhoClicked() instanceof Player p)
        || c == null
        || !registered.contains(key(c.id()))
        || !allowed(p, c)
        || !matchesCurrent((Player) e.getWhoClicked(), e.getInventory(), c)) e.setCancelled(true);
  }

  private boolean matchesCurrent(Player p, CraftingInventory inventory, Craft c) {
    // Re-resolve the live matrix: an inventory opened before recipe editing must not craft an old
    // result.
    var current = Bukkit.getCraftingRecipe(inventory.getMatrix(), p.getWorld());
    return current instanceof Keyed k
        && k.getKey().equals(key(c.id()))
        && current.getResult().equals(c.result().item(lang))
        && inventory.getResult() != null
        && inventory.getResult().equals(c.result().item(lang));
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    discover(e.getPlayer());
  }

  @EventHandler
  public void auth(dev.lovelace.lovecore.api.auth.PlayerAuthenticatedEvent e) {
    main.execute(() -> discover(e.player()));
  }

  @Override
  public void close() {
    for (var key : registered) Bukkit.removeRecipe(key);
    registered.clear();
    HandlerList.unregisterAll(this);
  }
}
