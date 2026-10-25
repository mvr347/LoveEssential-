package dev.lovelace.loveessential.module;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Skin textures straight from Mojang. Paper's {@code PlayerProfile#complete} only knows accounts
 * the server has seen when {@code online-mode} is off, so {@code /skin <name>} would work for
 * players who are online and nobody else.
 */
final class MojangSkins {
  record Textures(String value, String signature) {}

  private static final Duration TIMEOUT = Duration.ofSeconds(8);

  private MojangSkins() {}

  /** Textures of the account called {@code name}; empty when there is no such account or no skin. */
  static Optional<Textures> fetch(HttpClient client, String name) throws IOException, InterruptedException {
    var byName =
        client.send(
            request("https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(name, StandardCharsets.UTF_8)),
            HttpResponse.BodyHandlers.ofString());
    if (byName.statusCode() != 200) {
      if (byName.statusCode() == 204 || byName.statusCode() == 404) return Optional.empty();
      throw new IOException("Mojang answered " + byName.statusCode());
    }
    var id = parseId(byName.body());
    if (id.isEmpty()) return Optional.empty();
    var profile =
        client.send(
            request("https://sessionserver.mojang.com/session/minecraft/profile/" + id.get() + "?unsigned=false"),
            HttpResponse.BodyHandlers.ofString());
    if (profile.statusCode() != 200) {
      if (profile.statusCode() == 204 || profile.statusCode() == 404) return Optional.empty();
      throw new IOException("Mojang answered " + profile.statusCode());
    }
    return parseTextures(profile.body());
  }

  private static HttpRequest request(String url) {
    return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("Accept", "application/json").GET().build();
  }

  /** The undashed UUID of a {@code users/profiles/minecraft} answer. */
  static Optional<String> parseId(String json) {
    try {
      var object = JsonParser.parseString(json).getAsJsonObject();
      return object.has("id") ? Optional.of(object.get("id").getAsString()) : Optional.empty();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** The {@code textures} property of a session-server profile answer. */
  static Optional<Textures> parseTextures(String json) {
    try {
      var properties = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("properties");
      if (properties == null) return Optional.empty();
      for (var element : properties) {
        var property = element.getAsJsonObject();
        if (!"textures".equals(property.get("name").getAsString())) continue;
        String signature = property.has("signature") ? property.get("signature").getAsString() : "";
        return Optional.of(new Textures(property.get("value").getAsString(), signature));
      }
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }
}
