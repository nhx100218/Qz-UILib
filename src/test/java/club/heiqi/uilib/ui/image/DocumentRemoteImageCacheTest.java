package club.heiqi.uilib.ui.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import club.heiqi.uilib.ui.image.DocumentRemoteImageCache.Entry;
import club.heiqi.uilib.ui.image.DocumentRemoteImageCache.Status;

/**
 * 远程图片缓存的任务归属、失效与重试语义测试。
 *
 * <p>用本机回环上的 {@link HttpServer} 造可控网络条件（首次 500 再 200、404、阻塞响应），
 * 不依赖外网；每个用例自建自停服务端，互不影响。重点覆盖：</p>
 * <ul>
 *   <li>clear 使在飞任务失效——旧任务不发布结果、不回调旧消费者；</li>
 *   <li>临时失败只在下一次 request 上重试，退避期内不重复请求、尝试次数有界；</li>
 *   <li>终态失败（404）不重试；</li>
 *   <li>队列有界，队满拒绝时落为临时失败而不是阻塞调用线程；</li>
 *   <li>消费者回调不在缓存/条目锁内执行。</li>
 * </ul>
 */
public class DocumentRemoteImageCacheTest {

    private DocumentRemoteImageCache cache;
    private HttpServer server;
    private ExecutorService serverExecutor;
    private byte[] pngBytes;

    @Before
    public void setUp() throws Exception {
        cache = DocumentRemoteImageCache.getInstance();
        cache.clearForTesting();
        pngBytes = encodePng();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor = null;
        }
        cache.clearForTesting();
    }

    // ======================= C4：临时失败的有界重试 =======================

    /**
     * 临时失败（500）后：退避期内的 request 不重试；退避到期后的下一次 request 才重试，
     * 且重试复用同一条目（共享加载），成功只回调触发重试的那次请求。
     */
    @Test
    public void transientFailureRetriesOnlyOnNextRequestAfterBackoff() throws Exception {
        final AtomicInteger httpCalls = new AtomicInteger();
        String url = startServer(new Responder() {
            @Override
            public void respond(HttpExchange exchange) throws IOException {
                sendPng(exchange, httpCalls.incrementAndGet() == 1 ? 500 : 200);
            }
        });
        final AtomicInteger firstCallback = new AtomicInteger();
        final AtomicInteger retryCallback = new AtomicInteger();

        Entry entry = cache.request(url, new Runnable() {
            @Override
            public void run() {
                firstCallback.incrementAndGet();
            }
        });
        Assert.assertEquals("请求后应立即进入加载态", Status.LOADING, entry.getStatus());
        awaitStatus(entry, Status.FAILED);
        Assert.assertEquals("首次尝试应真的打到服务端", 1, httpCalls.get());

        Entry repeated = cache.request(url, null);
        Assert.assertSame("同一 URL 必须复用同一条目", entry, repeated);
        Assert.assertEquals("退避期内不得重试", Status.FAILED, entry.getStatus());
        Assert.assertEquals("退避期内不得再打服务端", 1, httpCalls.get());

        expireBackoff(entry);
        Assert.assertSame("重试仍走同一条目（重复请求合并）", entry, cache.request(url, new Runnable() {
            @Override
            public void run() {
                retryCallback.incrementAndGet();
            }
        }));
        Assert.assertEquals("退避到期后应重新进入加载态", Status.LOADING, entry.getStatus());
        awaitStatus(entry, Status.LOADED);
        Assert.assertNotNull("重试成功后条目应带位图", entry.getImage());
        Assert.assertEquals("首次请求的回调不因重试补发", 0, firstCallback.get());
        Assert.assertEquals("触发重试的那次请求才收到回调", 1, retryCallback.get());
        Assert.assertEquals("重试应真的再打一次服务端", 2, httpCalls.get());
    }

    /** 临时失败的尝试次数有界：到顶后停在 FAILED，再 request 也不打服务端。 */
    @Test
    public void transientFailureStopsAtAttemptLimit() throws Exception {
        final AtomicInteger httpCalls = new AtomicInteger();
        String url = startServer(new Responder() {
            @Override
            public void respond(HttpExchange exchange) throws IOException {
                httpCalls.incrementAndGet();
                sendPng(exchange, 503);
            }
        });
        Entry entry = cache.request(url, null);
        for (int attempt = 1; attempt <= 3; attempt++) {
            awaitStatus(entry, Status.FAILED);
            Assert.assertEquals("第 " + attempt + " 次尝试应已发生", attempt, httpCalls.get());
            expireBackoff(entry);
            cache.request(url, null);
        }

        awaitStatus(entry, Status.FAILED);
        expireBackoff(entry);
        cache.request(url, null);
        Assert.assertEquals("达到尝试上限后必须停在 FAILED", Status.FAILED, entry.getStatus());
        Assert.assertEquals("达到尝试上限后不得再打服务端", 3, httpCalls.get());
    }

    /** 终态失败（404）不做自动重试，即使退避时钟已过期。 */
    @Test
    public void permanentFailureIsNeverRetried() throws Exception {
        final AtomicInteger httpCalls = new AtomicInteger();
        String url = startServer(new Responder() {
            @Override
            public void respond(HttpExchange exchange) throws IOException {
                httpCalls.incrementAndGet();
                sendPng(exchange, 404);
            }
        });
        Entry entry = cache.request(url, null);
        awaitStatus(entry, Status.FAILED);

        expireBackoff(entry);
        cache.request(url, null);
        Assert.assertEquals("404 属终态失败", Status.FAILED, entry.getStatus());
        Assert.assertEquals("终态失败不得重试", 1, httpCalls.get());
    }

    // ======================= C3：旧任务失效与排队容量 =======================

    /** clear 之后，在飞的下载任务不得发布结果、不得回调旧消费者。 */
    @Test
    public void clearInvalidatesInFlightDownloadWithoutPublishingOrCallback() throws Exception {
        final CountDownLatch arrived = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch responded = new CountDownLatch(1);
        String url = startServer(new Responder() {
            @Override
            public void respond(HttpExchange exchange) throws IOException {
                arrived.countDown();
                awaitQuietly(release, 5L);
                sendPng(exchange, 200);
                responded.countDown();
            }
        });
        final AtomicInteger callbacks = new AtomicInteger();
        Entry entry = cache.request(url, new Runnable() {
            @Override
            public void run() {
                callbacks.incrementAndGet();
            }
        });
        Assert.assertTrue("下载应已开始", arrived.await(5L, TimeUnit.SECONDS));

        cache.clearForTesting();
        release.countDown();
        Assert.assertTrue("服务端应已写出响应", responded.await(5L, TimeUnit.SECONDS));
        // 留出「读流 → 解码 → 发布」的窗口：旧任务必须在发布前看到条目已弃用
        awaitQuietly(new CountDownLatch(1), 0.5D);

        Assert.assertEquals("清空后旧任务不得回调消费者", 0, callbacks.get());
        Assert.assertNull("清空后旧任务不得把位图写进条目", entry.getImage());
        Assert.assertNotEquals("清空后旧任务不得发布 LOADED", Status.LOADED, entry.getStatus());
    }

    /** 消费者回调不得在条目/缓存锁内执行：回调阻塞时 clear 仍须立即返回。 */
    @Test
    public void completionCallbackRunsOutsideTheEntryLock() throws Exception {
        String url = startServer(new Responder() {
            @Override
            public void respond(HttpExchange exchange) throws IOException {
                sendPng(exchange, 200);
            }
        });
        final CountDownLatch inCallback = new CountDownLatch(1);
        final CountDownLatch releaseCallback = new CountDownLatch(1);
        Entry entry = cache.request(url, new Runnable() {
            @Override
            public void run() {
                inCallback.countDown();
                awaitQuietly(releaseCallback, 5L);
            }
        });
        Assert.assertTrue("回调应被调用", inCallback.await(5L, TimeUnit.SECONDS));

        final AtomicReference<Throwable> clearFailure = new AtomicReference<Throwable>();
        Thread clearer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    cache.clearForTesting();
                } catch (Throwable error) {
                    clearFailure.set(error);
                }
            }
        }, "remote-image-clear-probe");
        clearer.start();
        clearer.join(1500L);
        Assert.assertFalse("回调执行期间不得持有缓存/条目锁（clear 不得被回调拖住）", clearer.isAlive());
        Assert.assertNull(clearFailure.get());
        releaseCallback.countDown();
        clearer.join(5000L);
        Assert.assertEquals("发布先于 clear：条目应保持已加载终态", Status.LOADED, entry.getStatus());
    }

    /**
     * 队列有界：队满时该次请求落为临时失败并立即返回，绝不阻塞调用线程。
     *
     * <p>用「唯一工作线程被占用 + 唯一队列位被占用」的饱和池替换内部执行器，确定性地触发拒绝；
     * 用例结束恢复原执行器。</p>
     */
    @Test
    public void saturatedQueueFailsEntryInsteadOfBlockingCaller() throws Exception {
        Field executorField = DocumentRemoteImageCache.class.getDeclaredField("executorService");
        executorField.setAccessible(true);
        ExecutorService original = (ExecutorService) executorField.get(cache);
        final CountDownLatch occupyThread = new CountDownLatch(1);
        ThreadPoolExecutor saturated = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(1), new ThreadPoolExecutor.AbortPolicy());
        saturated.execute(new Runnable() {
            @Override
            public void run() {
                awaitQuietly(occupyThread, 30L);
            }
        });
        saturated.execute(new Runnable() {
            @Override
            public void run() {
                // 占住唯一队列位
            }
        });
        executorField.set(cache, saturated);
        try {
            long startedAt = System.nanoTime();
            Entry entry = cache.request("http://127.0.0.1:1/saturated.png", null);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            Assert.assertEquals("队满应落为失败而不是排队等待", Status.FAILED, entry.getStatus());
            Assert.assertTrue("请求不得阻塞在缓存内，实测 " + elapsedMs + "ms", elapsedMs < 2000L);
        } finally {
            executorField.set(cache, original);
            occupyThread.countDown();
            saturated.shutdownNow();
        }
    }

    // ======================= 夹具 =======================

    /** 服务端响应策略 */
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    /** 启动本机回环上的单路径测试服务，返回请求 URL */
    private String startServer(final Responder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "remote-image-test-http");
                thread.setDaemon(true);
                return thread;
            }
        });
        server.setExecutor(serverExecutor);
        server.createContext("/img.png", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                responder.respond(exchange);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/img.png";
    }

    private void sendPng(HttpExchange exchange, int status) throws IOException {
        if (status != 200) {
            exchange.sendResponseHeaders(status, -1L);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, pngBytes.length);
        OutputStream body = exchange.getResponseBody();
        body.write(pngBytes);
        body.flush();
        exchange.close();
    }

    private static byte[] encodePng() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF123456);
        image.setRGB(1, 1, 0xFFABCDEF);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    private static void awaitStatus(Entry entry, Status expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            if (entry.getStatus() == expected) {
                return;
            }
            Thread.sleep(5L);
        }
        Assert.assertEquals("等待条目进入期望状态超时", expected, entry.getStatus());
    }

    /** 把重试退避时钟拨到已过期，避免测试真的等满 2s 退避；不改变生产语义。 */
    private static void expireBackoff(Entry entry) throws Exception {
        Field field = Entry.class.getDeclaredField("nextRetryAtNanos");
        field.setAccessible(true);
        field.setLong(entry, 0L);
    }

    private static void awaitQuietly(CountDownLatch latch, double seconds) {
        try {
            latch.await((long) (seconds * 1000D), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
