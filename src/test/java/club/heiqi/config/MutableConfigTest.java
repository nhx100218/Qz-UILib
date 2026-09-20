package club.heiqi.config;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 可变配置测试
 */
public class MutableConfigTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testCreateEmptyConfig() {
        MutableConfig config = Config.createMutable(ConfigFormat.JSON);
        
        assertNotNull(config);
        assertEquals(ConfigFormat.JSON, config.getFormat());
        assertNull(config.getSource());
        assertFalse(config.isDirty());
    }

    @Test
    public void testSetAndGet() throws ConfigException {
        MutableConfig config = Config.createMutable(ConfigFormat.JSON);

        // 设置值
        config.set("name", "test");
        config.set("value", 42);
        config.set("enabled", true);

        // 读取值
        assertEquals("test", config.get("name").asString());
        assertEquals(42, config.get("value").asInt());
        assertTrue(config.get("enabled").asBoolean());
        
        // 配置应该被标记为已修改
        assertTrue(config.isDirty());
    }

    @Test
    public void testSetNestedValues() throws ConfigException {
        MutableConfig config = Config.createMutable(ConfigFormat.JSON);

        // 设置嵌套值
        config.set("server.host", "localhost");
        config.set("server.port", 8080);
        config.set("database.credentials.username", "admin");

        // 读取嵌套值
        assertEquals("localhost", config.get("server.host").asString());
        assertEquals(8080, config.get("server.port").asInt());
        assertEquals("admin", config.get("database.credentials.username").asString());
    }

    @Test
    public void testRemove() throws ConfigException {
        MutableConfig config = Config.createMutable(ConfigFormat.JSON);

        config.set("name", "test");
        config.set("value", 42);

        assertTrue(config.has("name"));
        
        // 移除配置
        config.remove("name");
        
        assertFalse(config.has("name"));
        assertTrue(config.has("value"));
    }

    @Test
    public void testClear() throws ConfigException {
        MutableConfig config = Config.createMutable(ConfigFormat.JSON);

        config.set("name", "test");
        config.set("value", 42);
        config.set("enabled", true);

        // 清空配置
        config.clear();

        assertFalse(config.has("name"));
        assertFalse(config.has("value"));
        assertFalse(config.has("enabled"));
    }

    @Test
    public void testSaveAndLoadJson() throws Exception {
        File file = tempFolder.newFile("config.json");
        
        // 创建并保存配置
        MutableConfig config = Config.createMutable(file, ConfigFormat.JSON);
        config.set("server.host", "localhost");
        config.set("server.port", 8080);
        config.set("debug", true);
        config.save();

        assertFalse(config.isDirty());
        assertTrue(file.exists());

        // 重新加载配置
        MutableConfig loaded = Config.loadMutable(file);
        assertEquals("localhost", loaded.get("server.host").asString());
        assertEquals(8080, loaded.get("server.port").asInt());
        assertTrue(loaded.get("debug").asBoolean());
    }

    @Test
    public void testSaveAndLoadYaml() throws Exception {
        File file = tempFolder.newFile("config.yaml");
        
        // 创建并保存配置
        MutableConfig config = Config.createMutable(file, ConfigFormat.YAML);
        config.set("server.host", "localhost");
        config.set("server.port", 8080);
        config.set("debug", false);
        config.save();

        assertFalse(config.isDirty());
        assertTrue(file.exists());

        // 重新加载配置
        MutableConfig loaded = Config.loadMutable(file);
        assertEquals("localhost", loaded.get("server.host").asString());
        assertEquals(8080, loaded.get("server.port").asInt());
        assertFalse(loaded.get("debug").asBoolean());
    }

    @Test
    public void testReload() throws Exception {
        File file = tempFolder.newFile("config.json");
        
        // 创建并保存初始配置
        MutableConfig config = Config.createMutable(file, ConfigFormat.JSON);
        config.set("version", 1);
        config.save();

        // 修改内存中的配置
        config.set("version", 2);
        assertEquals(2, config.get("version").asInt());

        // 重新加载（应该恢复到文件中的值）
        config.reload();
        assertEquals(1, config.get("version").asInt());
        assertFalse(config.isDirty());
    }

    @Test
    public void testChangeListener() throws ConfigException {
        MutableConfig config = Config.createMutable(ConfigFormat.JSON);
        
        final AtomicInteger changeCount = new AtomicInteger(0);
        final String[] lastPath = new String[1];

        // 添加监听器
        config.addChangeListener(new ConfigChangeListener() {
            @Override
            public void onConfigChanged(ConfigChangeEvent event) {
                changeCount.incrementAndGet();
                lastPath[0] = event.getPath();
            }
        });

        // 触发变更
        config.set("name", "test");
        assertEquals(1, changeCount.get());
        assertEquals("name", lastPath[0]);

        config.set("value", 42);
        assertEquals(2, changeCount.get());
        assertEquals("value", lastPath[0]);

        config.remove("name");
        assertEquals(3, changeCount.get());
        assertEquals("name", lastPath[0]);
    }

    @Test
    public void testChainedOperations() throws ConfigException {
        MutableConfig config = Config.createMutable(ConfigFormat.JSON);

        // 链式调用
        config.set("name", "test")
              .set("value", 42)
              .set("enabled", true);

        assertEquals("test", config.get("name").asString());
        assertEquals(42, config.get("value").asInt());
        assertTrue(config.get("enabled").asBoolean());
    }

    @Test
    public void testAsImmutable() throws ConfigException {
        MutableConfig config = Config.createMutable(ConfigFormat.JSON);
        config.set("name", "test");
        config.set("value", 42);

        // 转换为不可变节点
        ConfigNode immutable = config.asImmutable();
        
        assertEquals("test", immutable.get("name").asString());
        assertEquals(42, immutable.get("value").asInt());
    }

    @Test
    public void testLoadNonExistentFile() throws Exception {
        File file = new File(tempFolder.getRoot(), "nonexistent.json");
        
        // 加载不存在的文件应该创建空配置
        MutableConfig config = Config.loadMutable(file);
        
        assertNotNull(config);
        assertFalse(config.has("anything"));
        
        // 可以设置值并保存
        config.set("created", true);
        config.save();
        
        assertTrue(file.exists());
    }

    @Test
    public void testDirtyFlag() throws Exception {
        File file = tempFolder.newFile("config.json");
        MutableConfig config = Config.createMutable(file, ConfigFormat.JSON);

        // 初始状态不是脏的
        assertFalse(config.isDirty());

        // 修改后变脏
        config.set("name", "test");
        assertTrue(config.isDirty());

        // 保存后变干净
        config.save();
        assertFalse(config.isDirty());

        // 再次修改后又变脏
        config.set("name", "updated");
        assertTrue(config.isDirty());
    }

    @Test
    public void testComplexStructure() throws Exception {
        File file = tempFolder.newFile("complex.json");
        MutableConfig config = Config.createMutable(file, ConfigFormat.JSON);

        // 设置复杂结构
        config.set("database.primary.host", "db1.example.com");
        config.set("database.primary.port", 3306);
        config.set("database.replica.host", "db2.example.com");
        config.set("database.replica.port", 3306);
        config.set("cache.enabled", true);
        config.set("cache.ttl", 300);

        config.save();

        // 重新加载验证
        MutableConfig loaded = Config.loadMutable(file);
        assertEquals("db1.example.com", loaded.get("database.primary.host").asString());
        assertEquals(3306, loaded.get("database.primary.port").asInt());
        assertEquals("db2.example.com", loaded.get("database.replica.host").asString());
        assertTrue(loaded.get("cache.enabled").asBoolean());
        assertEquals(300, loaded.get("cache.ttl").asInt());
    }

    /**
     * 回归：显式 null 键的删除必须同步数据、脏标记、不可变快照与变更事件。
     *
     * <p>显式 null 键（JSON/YAML 的 {@code "k": null}）在 data 里存的就是 Java null，删除成功
     * 与删除不存在时的 Map.remove 返回值完全相同；只看返回值会漏掉 dirty/pristine 更新，
     * 使 asImmutable/save 继续回退到带旧键的 originalNode。</p>
     */
    @Test
    public void removeExplicitNullKeyUpdatesDataDirtyAndSnapshot() throws Exception {
        for (ConfigFormat format : new ConfigFormat[] {ConfigFormat.JSON, ConfigFormat.YAML}) {
            for (String path : new String[] {"optional", "section.optional"}) {
                assertExplicitNullDeletion(format, path);
            }
        }
    }

    private void assertExplicitNullDeletion(ConfigFormat format, String path) throws Exception {
        String suffix = format == ConfigFormat.JSON ? ".json" : ".yaml";
        File file = tempFolder.newFile(format.name() + "_" + path.replace('.', '_') + suffix);
        writeUtf8(file, format == ConfigFormat.JSON
                ? "{\n  \"name\": \"test\",\n  \"optional\": null,\n  \"section\": {\"optional\": null}\n}\n"
                : "name: test\n# 顶层空值\noptional: null\nsection:\n  # 嵌套空值\n  optional: null\n");
        MutableConfig config = Config.loadMutable(file);
        String parentPath = path.startsWith("section.") ? "section" : "";
        ConfigNode original = config.asImmutable();
        assertFalse("刚加载的配置不是脏的", config.isDirty());
        assertTrue("显式 null 键在数据层存在", config.get(parentPath).asMap().containsKey("optional"));

        final List<ConfigChangeEvent> events = new ArrayList<ConfigChangeEvent>();
        config.addChangeListener(new ConfigChangeListener() {
            @Override
            public void onConfigChanged(ConfigChangeEvent event) {
                events.add(event);
            }
        });

        config.remove(path);

        assertFalse("删除后数据层不再含该键", config.get(parentPath).asMap().containsKey("optional"));
        assertFalse("删除后不可变快照也不得再含该键",
                config.asImmutable().get(parentPath).asMap().containsKey("optional"));
        assertTrue("旧快照保持加载期数据", original.get(parentPath).asMap().containsKey("optional"));
        assertTrue("删除显式 null 键必须置脏", config.isDirty());
        assertEquals("删除必须发出一次 REMOVE 事件", 1, events.size());
        assertEquals(ConfigChangeEvent.ChangeType.REMOVE, events.get(0).getType());
        assertEquals(path, events.get(0).getPath());
        assertNull("旧值本身按语义就是 null", events.get(0).getOldValue());
        assertNull("删除事件的新值为 null", events.get(0).getNewValue());

        config.markClean();
        assertFalse("标记干净不得恢复加载期快照",
                config.asImmutable().get(parentPath).asMap().containsKey("optional"));
        config.save();
        assertFalse("保存后清除脏标记", config.isDirty());
        assertFalse("保存后快照不得回退",
                config.asImmutable().get(parentPath).asMap().containsKey("optional"));
        MutableConfig reloaded = Config.loadMutable(file);
        assertFalse("磁盘上该键已删除", reloaded.get(parentPath).asMap().containsKey("optional"));
        assertEquals("test", reloaded.get("name").asString());
        config.remove(path);
        assertFalse("重复删除保持干净", config.isDirty());
        assertEquals("重复删除不得追加事件", 1, events.size());
    }

    /**
     * 回归（负例）：删除不存在的键保持无副作用——不置脏、不发事件、不破坏 pristine。
     */
    @Test
    public void removeMissingKeyKeepsPristineAndEmitsNothing() throws Exception {
        File file = tempFolder.newFile("noop.json");
        writeUtf8(file, "{\n  \"name\": \"test\"\n}\n");
        MutableConfig config = Config.loadMutable(file);

        final AtomicInteger changes = new AtomicInteger(0);
        config.addChangeListener(new ConfigChangeListener() {
            @Override
            public void onConfigChanged(ConfigChangeEvent event) {
                changes.incrementAndGet();
            }
        });

        ConfigNode original = config.asImmutable();
        config.remove("absent");
        config.remove("absent.nested");
        config.remove("name.nested");

        assertSame("无效删除保持 pristine，继续使用原快照", original, config.asImmutable());
        assertFalse("不存在的键删除后不得置脏", config.isDirty());
        assertEquals("不存在的键删除不得发事件", 0, changes.get());
        assertEquals("test", config.asImmutable().get("name").asString());
    }

    /**
     * 回归：改一个值再保存，未变路径的 YAML 注释（块注释 / 内联注释 / 列表元素注释 /
     * 显式 null 的注释）必须保留，且键顺序与数值类型保真。
     */
    @Test
    public void modifiedSaveKeepsUnrelatedYamlComments() throws Exception {
        File file = tempFolder.newFile("comments.yaml");
        writeUtf8(file, ""
                + "# 顶层：服务端配置\n"
                + "server:\n"
                + "  # 主机地址\n"
                + "  host: localhost # 内联：默认本地\n"
                + "  port: 8080\n"
                + "  fallback: 9090\n"
                + "client:\n"
                + "  # 客户端开关\n"
                + "  enabled: true\n"
                + "servers:\n"
                + "  # 第一台\n"
                + "  - name: primary\n"
                + "  # 第二台\n"
                + "  - name: replica\n"
                + "# 显式空值说明\n"
                + "optional: null # 显式空值\n"
                + "typed:\n"
                + "  ratio: 0.5\n"
                + "  count: 7\n");
        MutableConfig config = Config.loadMutable(file);

        ConfigNode original = config.asImmutable();
        config.set("server.host", "example.com");
        ConfigNode snapshot = config.asImmutable();
        assertNotSame("带注释的 null 重建为独立实例", original.get("optional"), snapshot.get("optional"));
        assertNull("重建不得污染共享空节点", NullConfigNode.INSTANCE.getBlockComment());
        assertNull("重建不得污染共享空节点内联注释", NullConfigNode.INSTANCE.getInlineComment());
        config.save();

        String saved = readUtf8(file);
        assertTrue("顶层块注释应保留: " + saved, saved.contains("顶层：服务端配置"));
        assertTrue("嵌套块注释应保留: " + saved, saved.contains("主机地址"));
        assertTrue("内联注释应保留: " + saved, saved.contains("内联：默认本地"));
        assertTrue("相邻路径的注释应保留: " + saved, saved.contains("客户端开关"));
        assertTrue("列表元素注释应保留: " + saved, saved.contains("第一台") && saved.contains("第二台"));
        assertTrue("显式 null 的块注释应保留: " + saved, saved.contains("显式空值说明"));
        assertTrue("修改后的值应写盘: " + saved, saved.contains("example.com"));
        assertTrue("内联注释仍须贴在被修改键的同一行: " + lineContaining(saved, "example.com"),
                lineContaining(saved, "example.com").contains("内联：默认本地"));
        assertTrue("未变路径保持加载顺序: " + saved, saved.indexOf("server:") < saved.indexOf("client:"));

        MutableConfig reloaded = Config.loadMutable(file);
        assertEquals("example.com", reloaded.get("server.host").asString());
        assertEquals(9090, reloaded.get("server.fallback").asInt());
        assertEquals("顶层加载顺序保留", new ArrayList<String>(original.asMap().keySet()),
                new ArrayList<String>(reloaded.asMap().keySet()));
        assertEquals("嵌套加载顺序保留", new ArrayList<String>(original.get("server").asMap().keySet()),
                new ArrayList<String>(reloaded.get("server").asMap().keySet()));
        assertTrue(reloaded.get("client.enabled").asBoolean());
        assertEquals("浮点类型保真", 0.5D, reloaded.get("typed.ratio").asDouble(), 0.0D);
        assertEquals("整数类型保真", 7L, reloaded.get("typed.count").asLong());
        assertEquals("primary", reloaded.get("servers").get(0).get("name").asString());
        assertEquals("replica", reloaded.get("servers").get(1).get("name").asString());
        assertNull("显式 null 仍是显式 null", reloaded.get("optional").asString());
        assertNotNull("重新加载后显式 null 节点仍带块注释",
                reloaded.asImmutable().asMap().get("optional").getBlockComment());
    }

    /**
     * 回归：删除某个键只让该路径的注释消失，兄弟路径注释不受影响。
     */
    @Test
    public void removedPathCommentDisappearsWithoutTouchingSiblings() throws Exception {
        File file = tempFolder.newFile("drop.yaml");
        writeUtf8(file, ""
                + "alpha:\n"
                + "  # 保留我\n"
                + "  keep: 1\n"
                + "# 将被删除\n"
                + "beta: 2\n");
        MutableConfig config = Config.loadMutable(file);

        config.remove("beta");
        config.save();

        String saved = readUtf8(file);
        assertTrue("兄弟路径注释应保留: " + saved, saved.contains("保留我"));
        assertFalse("被删路径的注释应随路径消失: " + saved, saved.contains("将被删除"));
        MutableConfig reloaded = Config.loadMutable(file);
        assertEquals(1, reloaded.get("alpha.keep").asInt());
        assertFalse("beta 已删除", reloaded.asMap().containsKey("beta"));
    }

    /**
     * 回归：列表注释按<b>位置</b>对齐——同位置替换 + 尾部追加后，注释仍贴在「第 i 个位置」，
     * 多出来的位置没有注释。（「被删掉的位置其注释消失」由 {@link #truncatedListDropsTailPositionComments}
     * 用列表缩短场景实际验证。）
     */
    @Test
    public void listCommentsFollowPositionAfterEdit() throws Exception {
        File file = tempFolder.newFile("list.yaml");
        writeUtf8(file, ""
                + "servers:\n"
                + "  # 第一台\n"
                + "  - primary\n"
                + "  # 第二台\n"
                + "  - replica\n");
        MutableConfig config = Config.loadMutable(file);

        List<Object> replaced = new ArrayList<Object>();
        replaced.add("primary-2");
        replaced.add("replica");
        replaced.add("third");
        config.set("servers", replaced);
        config.save();

        String saved = readUtf8(file);
        assertTrue("第 1 个位置的注释应保留: " + saved, saved.contains("第一台"));
        assertTrue("第 2 个位置的注释应保留: " + saved, saved.contains("第二台"));

        MutableConfig reloaded = Config.loadMutable(file);
        ConfigNode servers = reloaded.asImmutable().get("servers");
        assertEquals(3, servers.asList().size());
        assertEquals("primary-2", servers.get(0).asString());
        assertNotNull("第 0 位应承接原第 0 位的注释", servers.get(0).getBlockComment());
        assertTrue("第 0 位注释文本应保留",
                servers.get(0).getBlockComment().getValue().contains("第一台"));
        assertTrue("第 1 位注释文本应保留",
                servers.get(1).getBlockComment().getValue().contains("第二台"));
        assertNull("新增的第 2 位没有注释可承接", servers.get(2).getBlockComment());
    }

    /**
     * 回归：列表缩短时，被删掉的尾部位置其注释随之消失，保留位置的注释仍按位置对齐。
     * 序列化后重读并逐个下标核对注释归属，而不是只断言文本存在。
     */
    @Test
    public void truncatedListDropsTailPositionComments() throws Exception {
        File file = tempFolder.newFile("truncate.yaml");
        writeUtf8(file, ""
                + "servers:\n"
                + "  # 第一台\n"
                + "  - primary\n"
                + "  # 第二台\n"
                + "  - replica\n"
                + "  # 第三台\n"
                + "  - third\n");
        MutableConfig config = Config.loadMutable(file);

        List<Object> shortened = new ArrayList<Object>();
        shortened.add("primary");
        shortened.add("replica");
        config.set("servers", shortened);
        config.save();

        String saved = readUtf8(file);
        assertFalse("被删位置的注释应随位置消失: " + saved, saved.contains("第三台"));

        MutableConfig reloaded = Config.loadMutable(file);
        ConfigNode servers = reloaded.asImmutable().get("servers");
        assertEquals(2, servers.asList().size());
        assertTrue("第 0 位注释归属不变",
                servers.get(0).getBlockComment().getValue().contains("第一台"));
        assertTrue("第 1 位注释归属不变",
                servers.get(1).getBlockComment().getValue().contains("第二台"));
    }

    /**
     * 回归：删除某个键后在同一会话内同键重建，不得把已删除子树的注释复活；兄弟路径的注释
     * 仍归原位置。序列化后重读核对（旧注释消失 + 兄弟注释仍在 + 新值本身无注释）。
     */
    @Test
    public void recreatedDeletedPathDoesNotResurrectComments() throws Exception {
        File file = tempFolder.newFile("revive.yaml");
        writeUtf8(file, ""
                + "alpha:\n"
                + "  # 保留我\n"
                + "  keep: 1\n"
                + "# 将被删除\n"
                + "beta:\n"
                + "  # 子注释\n"
                + "  inner: 2\n");
        MutableConfig config = Config.loadMutable(file);

        config.remove("beta");
        config.set("beta", 3);
        config.save();

        String saved = readUtf8(file);
        assertFalse("被删除位置的注释不得复活: " + saved, saved.contains("将被删除"));
        assertFalse("被删除子树的注释不得复活: " + saved, saved.contains("子注释"));
        assertTrue("兄弟路径注释应保留: " + saved, saved.contains("保留我"));

        MutableConfig reloaded = Config.loadMutable(file);
        ConfigNode beta = reloaded.asImmutable().get("beta");
        assertEquals(3, beta.asInt());
        assertNull("同键重建的位置按新位置处理，没有注释", beta.getBlockComment());
        ConfigNode keep = reloaded.asImmutable().get("alpha").get("keep");
        assertNotNull("兄弟路径注释仍归原位置", keep.getBlockComment());
        assertTrue(keep.getBlockComment().getValue().contains("保留我"));
    }

    /**
     * 回归：clear 之后重建配置，加载期快照里所有位置的注释都不得复活（整棵树按新位置处理）。
     */
    @Test
    public void clearedSnapshotDoesNotResurrectAnyComment() throws Exception {
        File file = tempFolder.newFile("cleared.yaml");
        writeUtf8(file, ""
                + "# 顶层说明\n"
                + "alpha:\n"
                + "  # 保留我\n"
                + "  keep: 1\n"
                + "# 内联说明\n"
                + "beta: 2 # 行尾\n");
        MutableConfig config = Config.loadMutable(file);

        ConfigNode original = config.asImmutable();
        config.clear();
        config.set("alpha.keep", 1);
        config.set("beta", 2);
        assertNull("asMap 不得复活父位置注释", config.asMap().get("alpha").getBlockComment());
        assertNull("asMap 不得复活子位置注释", config.asMap().get("alpha").get("keep").getBlockComment());
        assertNull("asMap 不得复活行尾注释", config.asMap().get("beta").getInlineComment());
        assertNull("不可变快照同样丢弃旧注释", config.asImmutable().get("alpha.keep").getBlockComment());
        assertNotNull("clear 不得改写加载期快照", original.get("alpha.keep").getBlockComment());
        config.save();

        String saved = readUtf8(file);
        assertFalse("clear 后重建不得带回顶层注释: " + saved, saved.contains("顶层说明"));
        assertFalse("clear 后重建不得带回嵌套注释: " + saved, saved.contains("保留我"));
        assertFalse("clear 后重建不得带回键注释: " + saved, saved.contains("内联说明"));

        MutableConfig reloaded = Config.loadMutable(file);
        ConfigNode alpha = reloaded.asImmutable().get("alpha");
        assertNull("重建出的父节点无快照注释", alpha.getBlockComment());
        assertNull("重建出的子位置无快照注释", alpha.get("keep").getBlockComment());
        assertNull("重建出的键无快照注释", reloaded.asImmutable().get("beta").getBlockComment());
    }

    /**
     * 回归：父节点被替换成标量时，位置本身的块注释保留在新值上，而不再存在的子位置注释消失。
     */
    @Test
    public void replacingParentKeepsPositionCommentAndDropsChildComment() throws Exception {
        File file = tempFolder.newFile("replace.yaml");
        writeUtf8(file, ""
                + "# 服务端块\n"
                + "server:\n"
                + "  # 主机\n"
                + "  host: localhost\n");
        MutableConfig config = Config.loadMutable(file);

        config.set("server", "flat");
        config.save();

        String saved = readUtf8(file);
        assertFalse("子位置已不存在，其注释应消失: " + saved, saved.contains("主机"));
        assertTrue("位置本身的注释应保留: " + saved, saved.contains("服务端块"));

        MutableConfig reloaded = Config.loadMutable(file);
        ConfigNode server = reloaded.asImmutable().get("server");
        assertEquals("flat", server.asString());
        assertNotNull("位置注释随位置保留", server.getBlockComment());
        assertTrue(server.getBlockComment().getValue().contains("服务端块"));
    }

    /** 已有节点中的 collection 末尾注释可归位，但不能流入替换后的标量。 */
    @Test
    public void collectionEndCommentsStayWithCollections() throws Exception {
        ConfigNode original = Config.parse("section:\n  value: 1\nitems:\n  - first\n", ConfigFormat.YAML);
        CommentMeta sectionEnd = CommentMeta.block("section end", 0);
        CommentMeta listEnd = CommentMeta.block("list end", 0);
        ((AbstractConfigNode) original.get("section")).setEndComment(sectionEnd);
        ((AbstractConfigNode) original.get("items")).setEndComment(listEnd);
        MutableConfig config = new DefaultMutableConfig(original, ConfigFormat.YAML, null);

        config.set("section.value", 2);
        ConfigNode edited = config.asImmutable();
        assertSame("Map 末尾注释保留", sectionEnd, edited.get("section").getEndComment());
        assertSame("未变 List 末尾注释保留", listEnd, edited.get("items").getEndComment());
        assertEquals("加载期快照不被修改", 1, original.get("section.value").asInt());

        config.set("section", "flat");
        config.set("items", null);
        ConfigNode replaced = config.asImmutable();
        assertNull("Map 换标量后不承接 collection 末尾注释", replaced.get("section").getEndComment());
        assertNull("List 换 null 后不承接 collection 末尾注释", replaced.get("items").getEndComment());
        assertSame("已取出的快照不受后续修改影响", sectionEnd, edited.get("section").getEndComment());
        assertNull("共享空节点不受影响", NullConfigNode.INSTANCE.getEndComment());
    }

    private static void writeUtf8(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String readUtf8(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** 返回包含 needle 的第一行（用于断言注释与被修改的键仍同行） */
    private static String lineContaining(String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return "";
    }
}
