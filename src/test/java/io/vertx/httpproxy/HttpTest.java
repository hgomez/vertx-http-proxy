package io.vertx.httpproxy;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.impl.SocketAddressImpl;
import io.vertx.httpproxy.backend.BackendProvider;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import org.junit.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public abstract class HttpTest extends ProxyTestBase {

  protected boolean keepAlive = true;
  protected boolean pipelining = true;

  protected HttpClientOptions configProxyClientOptions(HttpClientOptions options) {
    return options.setKeepAlive(keepAlive).setPipelining(pipelining);
  }

  private HttpProxy startProxy(TestContext ctx, BackendProvider... backends) {
    HttpProxy proxy = HttpProxy.createProxy(vertx, options.setClientOptions(configProxyClientOptions(new HttpClientOptions())));
    for (BackendProvider backend : backends) {
      proxy.addBackend(backend);
    }
    Async async1 = ctx.async();
    proxy.listen(ctx.asyncAssertSuccess(p -> async1.complete()));
    async1.awaitSuccess();
    return proxy;
  }

  private BackendProvider startHttpBackend(TestContext ctx, int port, Handler<HttpServerRequest> handler) {
    HttpServer backendServer = vertx.createHttpServer(new HttpServerOptions().setPort(port).setHost("localhost"));
    backendServer.requestHandler(handler);
    Async async = ctx.async();
    backendServer.listen(ctx.asyncAssertSuccess(s -> async.complete()));
    async.awaitSuccess();
    return new BackendProvider() {
      @Override
      public void handle(ProxyRequest request) {
        request.handle(() -> new SocketAddressImpl(port, "localhost"));
      }
    };
  }

  private BackendProvider startTcpBackend(TestContext ctx, int port, Handler<NetSocket> handler) {
    NetServer backendServer = vertx.createNetServer(new HttpServerOptions().setPort(port).setHost("localhost"));
    backendServer.connectHandler(handler);
    Async async = ctx.async();
    backendServer.listen(ctx.asyncAssertSuccess(s -> async.complete()));
    async.awaitSuccess();
    return new BackendProvider() {
      @Override
      public void handle(ProxyRequest request) {
        request.handle(() -> new SocketAddressImpl(port, "localhost"));
      }
    };
  }

  @Test
  public void testNotfound(TestContext ctx) {
    startProxy(ctx);
    HttpClient client = vertx.createHttpClient();
    Async async = ctx.async();
    client.getNow(8080, "localhost", "/", resp -> {
      ctx.assertEquals(404, resp.statusCode());
      async.complete();
    });
  }

  @Test
  public void testGet(TestContext ctx) {
    BackendProvider backend = startHttpBackend(ctx, 8081, req -> {
      ctx.assertEquals("/somepath", req.uri());
      ctx.assertEquals("localhost:8081", req.host());
      req.response().end("Hello World");
    });
    startProxy(ctx, backend);
    HttpClient client = vertx.createHttpClient();
    Async async = ctx.async();
    client.getNow(8080, "localhost", "/somepath", resp -> {
      ctx.assertEquals(200, resp.statusCode());
      resp.bodyHandler(buff -> {
        ctx.assertEquals("Hello World", buff.toString());
        async.complete();
      });
    });
  }

  @Test
  public void testPost(TestContext ctx) {
    byte[] body = new byte[1024];
    Random random = new Random();
    random.nextBytes(body);
    Async async = ctx.async(2);
    BackendProvider backend = startHttpBackend(ctx, 8081, req -> {
      req.bodyHandler(buff -> {
        req.response().end();
        ctx.assertEquals(Buffer.buffer(body), buff);
        async.complete();
      });
    });
    startProxy(ctx, backend);
    HttpClient client = vertx.createHttpClient();
    HttpClientRequest req = client.get(8080, "localhost", "/", resp -> {
      ctx.assertEquals(200, resp.statusCode());
      resp.endHandler(v -> {
        async.complete();
      });
    });
    req.end(Buffer.buffer(body));
  }

  @Test
  public void testBackendClosesDuringUpload(TestContext ctx) {
    BackendProvider backend = startHttpBackend(ctx, 8081, req -> {
      AtomicInteger len = new AtomicInteger();
      req.handler(buff -> {
        if (len.addAndGet(buff.length()) == 1024) {
          req.connection().close();
        }
      });
    });
    startProxy(ctx, backend);
    HttpClient client = vertx.createHttpClient();
    AtomicBoolean responseReceived = new AtomicBoolean();
    HttpClientRequest req = client.post(8080, "localhost", "/", resp -> {
      ctx.assertEquals(502, resp.statusCode());
      responseReceived.set(true);
    });
    Async async = ctx.async();
    req.connectionHandler(conn -> {
      conn.closeHandler(v -> {
        ctx.assertTrue(responseReceived.get());
        async.complete();
      });
    });
    req.putHeader("Content-Length", "2048");
    req.write(Buffer.buffer(new byte[1024]));
  }

  @Test
  public void testClientClosesDuringUpload(TestContext ctx) {
    Async async = ctx.async();
    Async closeLatch = ctx.async();
    BackendProvider backend = startHttpBackend(ctx, 8081, req -> {
      req.response().closeHandler(v -> {
        async.complete();
      });
      req.handler(buff -> {
        if (!closeLatch.isCompleted()) {
          closeLatch.complete();
        }
      });
    });
    startProxy(ctx, backend);
    HttpClient client = vertx.createHttpClient();
    HttpClientRequest req = client.post(8080, "localhost", "/", resp -> ctx.fail());
    req.putHeader("Content-Length", "2048");
    req.write(Buffer.buffer(new byte[1024]));
    closeLatch.awaitSuccess(10000);
    req.connection().close();
  }

  @Test
  public void testClientClosesAfterUpload(TestContext ctx) {
    Async async = ctx.async();
    Async closeLatch = ctx.async();
    BackendProvider backend = startHttpBackend(ctx, 8081, req -> {
      req.endHandler(v -> {
        closeLatch.complete();
        vertx.setTimer(200, id -> {
          req.response().setChunked(true).write("partial response");
        });
      });
      req.response().closeHandler(v -> {
        async.complete();
      });
    });
    startProxy(ctx, backend);
    HttpClient client = vertx.createHttpClient();
    HttpClientRequest req = client.post(8080, "localhost", "/", resp -> ctx.fail());
    req.end(Buffer.buffer(new byte[1024]));
    closeLatch.awaitSuccess(10000);
    HttpConnection conn = req.connection();
    conn.close();
  }

  @Test
  public void testBackendRepliesIncorrectHttpVersion(TestContext ctx) {
    Async latch = ctx.async();
    BackendProvider backend = startTcpBackend(ctx, 8081, so -> {
      so.write("HTTP/1.2 200 OK\r\n\r\n");
      so.close();
    });
    startProxy(ctx, backend);
    HttpClient client = vertx.createHttpClient();
    client.getNow(8080, "localhost", "/", resp -> {
      ctx.assertEquals(502, resp.statusCode());
      resp.endHandler(v -> {
        latch.complete();
      });
    });
  }

  @Test
  public void testSuppressIncorrectWarningHeaders(TestContext ctx) {
    Async latch = ctx.async();
    BackendProvider backend = startHttpBackend(ctx, 8081, req -> {
      req.response()
          .putHeader("date", "Tue, 15 Nov 1994 08:12:30 GMT")
          .putHeader("warning", "199 Miscellaneous warning \"Tue, 15 Nov 1994 08:12:31 GMT\"")
        .end();
    });
    startProxy(ctx, backend);
    HttpClient client = vertx.createHttpClient();
    client.getNow(8080, "localhost", "/", resp -> {
      ctx.assertNotNull(resp.getHeader("date"));
      ctx.assertNull(resp.getHeader("warning"));
      latch.complete();
    });
  }

  @Test
  public void testAddMissingHeaderDate(TestContext ctx) {
    Async latch = ctx.async();
    BackendProvider backend = startHttpBackend(ctx, 8081, req -> {
      req.response()
//          .putHeader("date", "Tue, 15 Nov 1994 08:12:30 GMT")
//          .putHeader("warning", "199 Miscellaneous warning \"Tue, 15 Nov 1994 08:12:31 GMT\"")
          .end();
    });
    startProxy(ctx, backend);
    HttpClient client = vertx.createHttpClient();
    client.getNow(8080, "localhost", "/", resp -> {
      ctx.assertNotNull(resp.getHeader("date"));
      latch.complete();
    });
  }

  @Test
  public void testAddMissingHeaderDateFromWarning(TestContext ctx) {
    String expected = "Tue, 15 Nov 1994 08:12:31 GMT";
    Async latch = ctx.async();
    BackendProvider backend = startHttpBackend(ctx, 8081, req -> {
      req.response()
          .putHeader("warning", "199 Miscellaneous warning \"" + expected + "\"")
          .end();
    });
    startProxy(ctx, backend);
    HttpClient client = vertx.createHttpClient();
    client.getNow(8080, "localhost", "/", resp -> {
      ctx.assertEquals(expected, resp.getHeader("date"));
      latch.complete();
    });
  }

  @Test
  public void testChunkedTransferEncoding(TestContext ctx) {
    int num = 50;
    Async latch = ctx.async();
    BackendProvider backend = startHttpBackend(ctx, 8081, req -> {
      HttpServerResponse resp = req.response();
      resp.setChunked(true);
      AtomicInteger count = new AtomicInteger(0);
      vertx.setPeriodic(1, id -> {
        int val = count.getAndIncrement();
        if (val < num) {
          resp.write("chunk-" + val);
        } else {
          vertx.cancelTimer(id);
          resp.end();
        }
      });
    });
    startProxy(ctx, backend);
    HttpClient client = vertx.createHttpClient();
    StringBuilder sb = new StringBuilder();
    for (int i = 0;i < num;i++) {
      sb.append("chunk-").append(i);
    }
    client.getNow(8080, "localhost", "/", resp -> {
      ctx.assertEquals("chunked", resp.getHeader("transfer-encoding"));
      resp.handler(buff -> {
        String part = buff.toString();
        if (sb.indexOf(part) == 0) {
          sb.delete(0, part.length());
        } else {
          ctx.fail();
        }
      });
      resp.endHandler(v -> {
        ctx.assertEquals("", sb.toString());
        latch.complete();
      });
    });
  }

  @Test
  public void testIllegalClientHttpVersion(TestContext ctx) {
    BackendProvider backend = startHttpBackend(ctx, 8081, req -> {
      ctx.fail();
    });
    startProxy(ctx, backend);
    NetClient client = vertx.createNetClient();
    client.connect(8080, "localhost", ctx.asyncAssertSuccess(so -> {
      Buffer resp = Buffer.buffer();
      so.handler(resp::appendBuffer);
      so.closeHandler(v -> {
        ctx.assertTrue(resp.toString().startsWith("HTTP/1.1 501 Not Implemented\r\n"));
      });
      so.write("GET /somepath http/1.1\r\n\r\n");
    }));
  }
}
