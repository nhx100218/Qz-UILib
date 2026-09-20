package club.heiqi.uilib.ui.image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import club.heiqi.uilib.MyMod;

/**
 * `img[src]` 远程位图下载缓存。
 *
 * <h2>生命周期：缓存条目 / 下载任务 / 消费者</h2>
 *
 * <p>三者的关系是本类的核心契约，改动前先读这里：</p>
 * <ul>
 *   <li><b>条目（{@link Entry}）</b>是共享单元：同一 URL 的所有消费者拿到同一个 Entry，
 *       因此「重复请求合并」不是特判，而是共享条目的自然结果（{@code markLoading} 只在
 *       PENDING→LOADING 的那一次为 true，只有一个任务会被提交）。</li>
 *   <li><b>下载任务</b>归属它提交时的那个条目，并被条目反向持有（{@link Entry#future}），
 *       以便在 {@link #clear()} / LRU 淘汰时取消「尚未执行」的排队任务。</li>
 *   <li><b>消费者</b>只读条目的 volatile 终态（{@link Entry#getStatus()} / {@link Entry#getImage()}），
 *       在 owner 线程消费；卸载单个消费者<b>不得</b>取消其他人仍在用的共享请求，故只有
 *       清空与淘汰会失效条目，消费者卸载不会。</li>
 * </ul>
 *
 * <h2>失效来源（缓存答得出「让谁跳过什么工作」）</h2>
 * <ul>
 *   <li>{@link #clear()} / 条目淘汰：把条目置为 abandoned 并取消其排队任务，让<b>还在飞或
 *       尚未开始</b>的下载任务跳过「发请求 / 解码 / 发布结果」全部工作——旧任务不会再
 *       发布 {@code LOADED/FAILED}，也不会再回调旧消费者。仅清 map 是不够的：那只是让新
 *       请求看不到旧条目，旧任务仍会继续下载并回调。</li>
 *   <li>队列容量（{@link #MAX_QUEUED_DOWNLOADS}）：条目上限（{@link #MAX_CACHE_ENTRIES}）
 *       管不住排队任务（每个排队任务都强引用自己的 Entry 与回调），因此本类同时对<b>排队长度</b>
 *       设界；队满时该次请求直接落为「临时失败」，由下一次 request 在退避后重试，
 *       调用线程绝不阻塞在缓存内。</li>
 *   <li>回调线程：{@link #loadRemoteImage} 只在<b>发布成功之后</b>、且已退出条目监视器与
 *       缓存锁时执行回调，绝不在锁内跑消费者代码。</li>
 * </ul>
 */
public final class DocumentRemoteImageCache {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_CACHE_ENTRIES = 128;
    /** 排队任务上限：与 MAX_CACHE_ENTRIES 是两道不同的闸门（见类注释「失效来源」）。 */
    private static final int MAX_QUEUED_DOWNLOADS = 32;
    /** 临时失败的最大尝试次数（含首次）。到顶后条目停在 FAILED，不再自动重试。 */
    private static final int MAX_TRANSIENT_ATTEMPTS = 3;
    /** 临时失败的重试退避基准：按尝试次数翻倍，封顶 RETRY_BACKOFF_MAX_MS。 */
    private static final long RETRY_BACKOFF_BASE_MS = 2000L;
    private static final long RETRY_BACKOFF_MAX_MS = 30000L;

    private static final DocumentRemoteImageCache INSTANCE = new DocumentRemoteImageCache();

    private final Map<String, Entry> entries = new ConcurrentHashMap<String, Entry>();
    private final AtomicBoolean trimInProgress = new AtomicBoolean(false);
    private final Object executorLock = new Object();
    private ExecutorService executorService = createExecutorService();

    private DocumentRemoteImageCache() {}

    /**
     * 返回共享远程图片缓存。
     *
     * @return 远程图片缓存
     */
    public static DocumentRemoteImageCache getInstance() {
        return INSTANCE;
    }

    /**
     * 请求远程图片；未完成时返回当前缓存状态。
     *
     * <p>若命中一条「临时失败且退避期已过、尝试次数未到顶」的条目，本方法会把它重新推回加载
     * 状态并提交一次新任务——这就是<b>唯一</b>的临时失败恢复入口：只在调用方再次 request
     * （如消费者重新挂载）时发生，不做每帧轮询，也不替已挂载页面自动兜底。退避期内的重复
     * request、以及终态失败 / 已达尝试上限的条目，一律原样返回旧条目。</p>
     *
     * @param url 图片 URL
     * @param completionCallback 本次请求成功加载完成后的回调；失败不回调，重试成功也只回调
     *                           「触发该次重试的那次请求」传来的回调（消费者应以条目状态为准）
     * @return 缓存条目
     */
    public Entry request(String url, Runnable completionCallback) {
        String normalizedUrl = normalizeUrl(url);
        if (normalizedUrl == null) {
            return Entry.failed();
        }
        trimCacheIfNeeded();
        Entry entry = entries.get(normalizedUrl);
        if (entry == null) {
            Entry createdEntry = new Entry(normalizedUrl);
            Entry previousEntry = entries.putIfAbsent(normalizedUrl, createdEntry);
            entry = previousEntry == null ? createdEntry : previousEntry;
        }
        entry.lastAccessedAt = System.nanoTime();
        entry.reviveForRetryIfDue(System.nanoTime());
        if (entry.markLoading()) {
            submitLoadTask(entry, completionCallback);
        }
        return entry;
    }

    /**
     * 为测试或宿主预热写入远程图片缓存。
     *
     * @param url 图片 URL
     * @param image 位图
     */
    public void putForTesting(String url, BufferedImage image) {
        String normalizedUrl = normalizeUrl(url);
        if (normalizedUrl == null || image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return;
        }
        Entry entry = new Entry(normalizedUrl);
        entry.markLoaded(image);
        Entry replaced = entries.put(normalizedUrl, entry);
        if (replaced != null) {
            // 被替换的旧条目可能还有在飞任务：一并发弃用，避免旧任务把结果发进已不在 map 的条目
            replaced.abandon();
        }
    }

    /**
     * 清空远程图片缓存条目，并使所有在飞 / 排队任务失效。
     *
     * <p>该入口只清理缓存数据，不关停下载线程池；适合客户端断连、世界切换或测试隔离。
     * JVM 退出阶段才应调用 {@link #shutdown()}。失效语义见类注释「失效来源」：清空之后旧任务
     * 不再发起请求、不再发布结果、不再回调。</p>
     */
    public void clear() {
        abandonAllAndClear();
    }

    /**
     * 清空缓存，供测试隔离使用。
     */
    public void clearForTesting() {
        clear();
    }

    /**
     * 关停内部远程位图下载线程池。
     *
     * <p>该入口只用于 JVM 退出阶段，由 {@link club.heiqi.uilib.ClientProxy} 的 shutdown hook
     * 调用。若诊断或特殊宿主在运行期误调用，后续 {@link #request(String, Runnable)} 会按需重建
     * 下载线程池，避免进程级单例进入不可恢复状态；普通断连清理请使用 {@link #clear()}。</p>
     */
    public void shutdown() {
        ExecutorService executorToShutdown;
        synchronized (executorLock) {
            executorToShutdown = executorService;
            executorService = null;
        }
        if (executorToShutdown == null) {
            abandonAllAndClear();
            return;
        }
        executorToShutdown.shutdown();
        try {
            if (!executorToShutdown.awaitTermination(2L, TimeUnit.SECONDS)) {
                executorToShutdown.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executorToShutdown.shutdownNow();
            Thread.currentThread().interrupt();
        }
        abandonAllAndClear();
    }

    /**
     * 为测试写入终态失败状态，便于验证图片回退逻辑。
     *
     * <p>写入的是<b>不可重试</b>的失败：既有失败回退用例要求同一条目在后续 request 中保持
     * FAILED（不得因临时失败重试语义而真的发起网络请求）。</p>
     *
     * @param url 图片 URL
     */
    public void putFailedForTesting(String url) {
        String normalizedUrl = normalizeUrl(url);
        if (normalizedUrl == null) {
            return;
        }
        Entry entry = new Entry(normalizedUrl);
        entry.markFailed(FailureKind.PERMANENT);
        Entry replaced = entries.put(normalizedUrl, entry);
        if (replaced != null) {
            replaced.abandon();
        }
    }

    /** 逐条弃用并清空缓存表；不关停线程池（关停只属 {@link #shutdown()}）。 */
    private void abandonAllAndClear() {
        for (Entry entry : entries.values()) {
            entry.abandon();
        }
        entries.clear();
    }

    /**
     * 提交下载任务，并把 Future 挂回条目以便之后取消。
     *
     * <p>队列已满（或在途被 shutdown 后仍拒绝）时按「临时失败」落账并返回：调用线程不阻塞、
     * 不抛异常，恢复入口是下一次 request 的退避重试。</p>
     */
    private void submitLoadTask(final Entry entry, final Runnable completionCallback) {
        if (entry.isAbandoned()) {
            return;
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                loadRemoteImage(entry, completionCallback);
            }
        };
        Future<?> future;
        try {
            synchronized (executorLock) {
                future = ensureExecutorServiceLocked().submit(task);
            }
        } catch (RejectedExecutionException exception) {
            // 运行期 shutdown 后按需重建一次（既有语义）；仍被拒绝说明队列已满
            try {
                synchronized (executorLock) {
                    future = ensureExecutorServiceLocked().submit(task);
                }
            } catch (RejectedExecutionException rejected) {
                entry.markFailed(FailureKind.TRANSIENT);
                return;
            }
        }
        entry.setFuture(future);
    }

    private ExecutorService ensureExecutorServiceLocked() {
        if (executorService == null || executorService.isShutdown() || executorService.isTerminated()) {
            executorService = createExecutorService();
        }
        return executorService;
    }

    private static ExecutorService createExecutorService() {
        // 队列有界：MAX_QUEUED_DOWNLOADS 之外的提交由调用点按「临时失败」处理，
        // 不用无界队列把「条目上限」当成队列上限（后者压根管不住排队任务）。
        return new ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(MAX_QUEUED_DOWNLOADS),
                new RemoteImageThreadFactory());
    }

    private void loadRemoteImage(Entry entry, Runnable completionCallback) {
        // 失效检查一：开始工作前。排队期间被 clear/淘汰/替换的任务在这里直接结束，不发请求。
        if (entry.isAbandoned()) {
            return;
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(entry.url);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(true);
            connection.setRequestProperty("User-Agent", "Qz-UILib img");
            int responseCode = connection.getResponseCode();
            if (entry.isAbandoned()) {
                return;
            }
            if (responseCode < 200 || responseCode >= 300) {
                // 5xx / 408 / 429 视为临时失败（服务端瞬时不可用或限流），其余非 2xx 视为终态
                // （地址不对、被拒），重试同 URL 没有意义。
                entry.markFailed(isTransientHttpStatus(responseCode) ? FailureKind.TRANSIENT
                        : FailureKind.PERMANENT);
                return;
            }
            BufferedImage image = ImageIO.read(connection.getInputStream());
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                // 内容不是可用位图：重取同一份字节大概率仍然解不出，按终态处理
                entry.markFailed(FailureKind.PERMANENT);
                return;
            }
            // 失效检查二：发布结果前。publishLoaded 与 abandon 共用条目监视器，
            // 「弃用与发布」是原子的——clear 之后旧任务不可能再把 LOADED 写进条目。
            if (entry.publishLoaded(image) && completionCallback != null) {
                // 回调已退出条目监视器与缓存锁；消费者代码不得在锁内跑。
                completionCallback.run();
            }
        } catch (IOException exception) {
            // 连接/读取/解码 IO 失败都属临时故障（断网、超时、服务端中途断开）：下一次 request 重试
            if (entry.markFailed(FailureKind.TRANSIENT)) {
                MyMod.LOG.warn("远程 img 位图加载失败: {}", entry.url, exception);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** HTTP 状态码是否属「可能自愈」：服务端错误与超时/限流。 */
    private static boolean isTransientHttpStatus(int responseCode) {
        return responseCode >= 500 || responseCode == 408 || responseCode == 429;
    }

    private void trimCacheIfNeeded() {
        if (entries.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        if (!trimInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            while (entries.size() > MAX_CACHE_ENTRIES) {
                String oldestKey = null;
                long oldestAccess = Long.MAX_VALUE;
                for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
                    long accessed = mapEntry.getValue().lastAccessedAt;
                    if (accessed < oldestAccess) {
                        oldestAccess = accessed;
                        oldestKey = mapEntry.getKey();
                    }
                }
                if (oldestKey == null) {
                    break;
                }
                Entry evicted = entries.remove(oldestKey);
                if (evicted != null) {
                    // 淘汰同样使旧任务失效：否则被淘汰的条目仍会下载、解码并回调
                    evicted.abandon();
                }
            }
        } finally {
            trimInProgress.set(false);
        }
    }

    private static String normalizeUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            return trimmed;
        }
        return null;
    }

    /** 失败分类：决定下一次 request 是否可能重试。 */
    private enum FailureKind {
        /** 临时故障（网络 IO、5xx、408、429、队满拒绝）：退避后可由下一次 request 重试 */
        TRANSIENT,
        /** 终态故障（4xx、解码失败）：同 URL 重试无意义，不再自动恢复 */
        PERMANENT
    }

    /**
     * 远程图片缓存条目。
     *
     * <p>状态迁移（全部在条目监视器内完成，消费者只读 volatile 终态）：</p>
     * <pre>
     * PENDING --markLoading--> LOADING --publishLoaded--> LOADED（终态）
     *                                    --markFailed(TRANSIENT)--> FAILED --revive(退避到期且未到尝试上限)--> PENDING
     *                                    --markFailed(PERMANENT)--> FAILED（终态）
     * 任意状态 --abandon--> abandoned（终态；之后不再发布结果、不再回调）
     * </pre>
     */
    public static final class Entry {

        private final String url;
        private volatile BufferedImage image;
        private volatile Status status = Status.PENDING;
        volatile long lastAccessedAt;
        /** 提交该条目的下载任务句柄；clear/淘汰时用于取消尚未执行的排队任务 */
        private Future<?> future;
        /** 是否已被 clear/淘汰/替换弃用：旧任务据此跳过发布与回调 */
        private boolean abandoned;
        private FailureKind failureKind = FailureKind.PERMANENT;
        /** 重试退避到期时刻（System.nanoTime 口径）；仅 TRANSIENT 失败有意义 */
        private long nextRetryAtNanos = Long.MAX_VALUE;
        /** 已发起的下载尝试次数（含首次）；到 MAX_TRANSIENT_ATTEMPTS 后不再自动重试 */
        private int attempts;

        private Entry(String url) {
            this.url = url;
            this.lastAccessedAt = System.nanoTime();
        }

        private static Entry failed() {
            Entry entry = new Entry("");
            entry.status = Status.FAILED;
            return entry;
        }

        public BufferedImage getImage() {
            return image;
        }

        public Status getStatus() {
            return status;
        }

        /** 条目是否已被弃用（clear/淘汰/替换）；旧任务在开始与发布前都要核对。 */
        boolean isAbandoned() {
            synchronized (this) {
                return abandoned;
            }
        }

        /**
         * 临时失败后由下一次 request 触发的复活：只在「FAILED + 临时失败 + 退避已过 +
         * 尝试次数未到顶」时把状态推回 PENDING，返回是否有资格重试。
         */
        private synchronized boolean reviveForRetryIfDue(long nowNanos) {
            if (abandoned || status != Status.FAILED || failureKind != FailureKind.TRANSIENT) {
                return false;
            }
            if (attempts >= MAX_TRANSIENT_ATTEMPTS || nowNanos < nextRetryAtNanos) {
                return false;
            }
            status = Status.PENDING;
            return true;
        }

        private synchronized boolean markLoading() {
            if (abandoned || status != Status.PENDING) {
                return false;
            }
            status = Status.LOADING;
            attempts++;
            return true;
        }

        private void markLoaded(BufferedImage image) {
            publishLoaded(image);
        }

        /** 发布成功结果；条目已弃用时返回 false（调用方不得再回调消费者）。 */
        private synchronized boolean publishLoaded(BufferedImage image) {
            if (abandoned) {
                return false;
            }
            this.image = image;
            this.status = Status.LOADED;
            return true;
        }

        /** 记录失败并按分类安排退避；条目已弃用时返回 false（调用方不必再记日志/回调）。 */
        private synchronized boolean markFailed(FailureKind kind) {
            if (abandoned) {
                return false;
            }
            this.status = Status.FAILED;
            this.failureKind = kind;
            if (kind == FailureKind.TRANSIENT) {
                long backoffMs = Math.min(RETRY_BACKOFF_MAX_MS,
                        RETRY_BACKOFF_BASE_MS << Math.max(0, attempts - 1));
                this.nextRetryAtNanos = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(backoffMs);
            } else {
                this.nextRetryAtNanos = Long.MAX_VALUE;
            }
            return true;
        }

        /** 测试夹具沿用：不带分类的失败按终态处理，避免既有回退用例意外触发真实重试。 */
        private void markFailed() {
            markFailed(FailureKind.PERMANENT);
        }

        /** 挂入任务句柄；若此间条目已被弃用，则立即取消该任务。 */
        private synchronized void setFuture(Future<?> future) {
            if (abandoned) {
                future.cancel(false);
                return;
            }
            this.future = future;
        }

        /**
         * 弃用条目：置 abandoned 并取消尚未执行的排队任务。
         *
         * <p>只 {@code cancel(false)}（不打断正在跑的下载）——正在执行的 IO 会自然结束，
         * 并在发布前看到 abandoned 而放弃发布。取消动作不持缓存锁，也不会触发任何回调。</p>
         */
        private synchronized void abandon() {
            abandoned = true;
            Future<?> pending = future;
            future = null;
            if (pending != null) {
                pending.cancel(false);
            }
        }
    }

    /**
     * 远程图片加载状态。
     */
    public enum Status {
        PENDING,
        LOADING,
        LOADED,
        FAILED
    }

    private static final class RemoteImageThreadFactory implements ThreadFactory {

        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "QzRemoteImage-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
