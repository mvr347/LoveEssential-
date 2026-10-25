package dev.lovelace.loveessential.config;

/**
 * A setting that fails the load-time checks. The message stays the language key {@code errors.config}
 * so older handlers keep working; {@link #detail()} names the key and the problem for the admin.
 */
public final class ConfigError extends IllegalArgumentException {
  private final String detail;

  public ConfigError(String detail) {
    super("errors.config");
    this.detail = detail;
  }

  public String detail() {
    return detail;
  }

  /** {@code path: value (allowed min…max)} for a number outside its range. */
  public static ConfigError range(String path, Object value, Object min, Object max) {
    return new ConfigError(path + ": " + value + " (допустимо " + min + "…" + max + ")");
  }
}
