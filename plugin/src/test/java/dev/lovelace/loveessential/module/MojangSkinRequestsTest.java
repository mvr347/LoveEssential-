package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MojangSkinRequestsTest {
  private static final String ACCOUNT = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\"}";
  private static final String TEXTURES = "{\"properties\":[{\"name\":\"textures\",\"value\":\"EXACT_SLIM_VALUE\",\"signature\":\"EXACT_SIGNATURE\"}]}";

  @BeforeEach void resetCache() throws Exception {
    var cache = MojangSkins.class.getDeclaredField("CACHE");
    cache.setAccessible(true);
    ((Map<?, ?>) cache.get(null)).clear();
    var retry = MojangSkins.class.getDeclaredField("retryAt");
    retry.setAccessible(true);
    retry.setLong(null, 0);
  }

  @Test void repeatedLookupsReuseSignedTexturesAndOnlyQueryTheRealAccountId() throws Exception {
    var client = new FakeClient();
    client.answer(200, ACCOUNT);
    client.answer(200, TEXTURES);
    var skin = MojangSkins.fetch(client, "Notch").orElseThrow();
    assertEquals("EXACT_SLIM_VALUE", skin.value());
    assertEquals("EXACT_SIGNATURE", skin.signature());
    assertEquals(skin, MojangSkins.fetch(client, "NOTCH").orElseThrow());
    assertEquals(2, client.requests.size());
    assertEquals("/users/profiles/minecraft/Notch", client.requests.getFirst().uri().getPath());
    assertTrue(client.requests.getLast().uri().toString().endsWith("069a79f444e94726a5befca90e38aaf5?unsigned=false"));
  }

  @Test void rateLimitStopsFurtherRequestsWhileCachedSkinsRemainAvailable() throws Exception {
    var client = new FakeClient();
    client.answer(200, ACCOUNT);
    client.answer(200, TEXTURES);
    var saved = MojangSkins.fetch(client, "Notch");
    client.answer(429, "<html>Service unavailable</html>");
    assertThrows(IOException.class, () -> MojangSkins.fetch(client, "Another"));
    assertThrows(IOException.class, () -> MojangSkins.fetch(client, "Third"));
    assertEquals(saved, MojangSkins.fetch(client, "Notch"));
    assertEquals(3, client.requests.size());
  }

  @Test void aMissingAccountIsCachedButMalformedSuccessIsNeverTreatedAsASkinReset() throws Exception {
    var client = new FakeClient();
    client.answer(404, "");
    assertTrue(MojangSkins.fetch(client, "Absent").isEmpty());
    assertTrue(MojangSkins.fetch(client, "Absent").isEmpty());
    assertEquals(1, client.requests.size());
    client.answer(200, "<html>Unavailable</html>");
    assertThrows(IOException.class, () -> MojangSkins.fetch(client, "Broken"));
    client.answer(200, ACCOUNT);
    client.answer(200, "{\"properties\":[{\"name\":\"textures\",\"value\":\"unsigned\"}]}");
    assertThrows(IOException.class, () -> MojangSkins.fetch(client, "Broken"));
  }

  private static final class FakeClient extends HttpClient {
    private record Reply(int status, String body) {}
    final List<HttpRequest> requests = new ArrayList<>();
    final Deque<Reply> replies = new ArrayDeque<>();
    void answer(int status, String body) { replies.add(new Reply(status, body)); }
    @Override @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      requests.add(request);
      Reply reply = replies.remove();
      return new HttpResponse<>() {
        public int statusCode() { return reply.status(); }
        public T body() { return (T) reply.body(); }
        public HttpRequest request() { return request; }
        public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of("Retry-After", List.of("120")), (a,b) -> true); }
        public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        public java.net.URI uri() { return request.uri(); }
        public Version version() { return Version.HTTP_1_1; }
      };
    }
    public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
    public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
    public Redirect followRedirects() { return Redirect.NEVER; }
    public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
    public javax.net.ssl.SSLContext sslContext() { throw new UnsupportedOperationException(); }
    public javax.net.ssl.SSLParameters sslParameters() { throw new UnsupportedOperationException(); }
    public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
    public Version version() { return Version.HTTP_1_1; }
    public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
  }
}
