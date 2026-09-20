package club.heiqi.config;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * YAML 配置加载器测试
 */
public class YamlConfigLoaderTest {

    @Test
    public void testLoadSimpleYaml() throws ConfigException {
        String yaml = "name: test\n" +
                      "value: 42\n" +
                      "enabled: true";
        ConfigNode node = Config.parse(yaml, ConfigFormat.YAML);

        assertNotNull(node);
        assertEquals(ConfigNode.NodeType.MAP, node.getType());
        assertEquals("test", node.get("name").asString());
        assertEquals(42, node.get("value").asInt());
        assertTrue(node.get("enabled").asBoolean());
    }

    @Test
    public void testLoadNestedYaml() throws ConfigException {
        String yaml = "server:\n" +
                      "  host: localhost\n" +
                      "  port: 8080";
        ConfigNode node = Config.parse(yaml, ConfigFormat.YAML);

        assertEquals("localhost", node.get("server.host").asString());
        assertEquals(8080, node.get("server.port").asInt());
    }

    @Test
    public void testLoadYamlList() throws ConfigException {
        String yaml = "items:\n" +
                      "  - 1\n" +
                      "  - 2\n" +
                      "  - 3\n" +
                      "  - 4\n" +
                      "  - 5";
        ConfigNode node = Config.parse(yaml, ConfigFormat.YAML);

        ConfigNode items = node.get("items");
        assertEquals(ConfigNode.NodeType.LIST, items.getType());
        assertEquals(5, items.asList().size());
        assertEquals(1, items.get(0).asInt());
        assertEquals(5, items.get(4).asInt());
    }

    @Test
    public void testLoadComplexYaml() throws ConfigException {
        String yaml = "database:\n" +
                      "  host: localhost\n" +
                      "  port: 3306\n" +
                      "  credentials:\n" +
                      "    username: admin\n" +
                      "    password: secret\n" +
                      "features:\n" +
                      "  - auth\n" +
                      "  - logging\n" +
                      "  - cache\n" +
                      "debug: false";

        ConfigNode node = Config.parse(yaml, ConfigFormat.YAML);

        assertEquals("localhost", node.get("database.host").asString());
        assertEquals(3306, node.get("database.port").asInt());
        assertEquals("admin", node.get("database.credentials.username").asString());
        assertEquals("secret", node.get("database.credentials.password").asString());
        
        ConfigNode features = node.get("features");
        assertEquals(3, features.asList().size());
        assertEquals("auth", features.get(0).asString());
        
        assertFalse(node.get("debug").asBoolean());
    }

    @Test
    public void testYamlComments() throws ConfigException {
        String yaml = "# This is a comment\n" +
                      "name: test  # inline comment\n" +
                      "value: 42";
        ConfigNode node = Config.parse(yaml, ConfigFormat.YAML);

        assertEquals("test", node.get("name").asString());
        assertEquals(42, node.get("value").asInt());
    }

    @Test
    public void testYamlQuotedStrings() throws ConfigException {
        String yaml = "single: 'single quoted'\n" +
                      "double: \"double quoted\"\n" +
                      "unquoted: no quotes";
        ConfigNode node = Config.parse(yaml, ConfigFormat.YAML);

        assertEquals("single quoted", node.get("single").asString());
        assertEquals("double quoted", node.get("double").asString());
        assertEquals("no quotes", node.get("unquoted").asString());
    }

    @Test
    public void testYamlBooleanValues() throws ConfigException {
        String yaml = "bool1: true\n" +
                      "bool2: false\n" +
                      "bool3: yes\n" +
                      "bool4: no";
        ConfigNode node = Config.parse(yaml, ConfigFormat.YAML);

        assertTrue(node.get("bool1").asBoolean());
        assertFalse(node.get("bool2").asBoolean());
        assertTrue(node.get("bool3").asBoolean());
        assertFalse(node.get("bool4").asBoolean());
    }

    @Test
    public void testYamlNullValues() throws ConfigException {
        String yaml = "null1: null\n" +
                      "null2: ~";
        ConfigNode node = Config.parse(yaml, ConfigFormat.YAML);

        assertTrue(node.get("null1").isNull());
        assertTrue(node.get("null2").isNull());
    }

    /**
     * 回归：带注释的 null 值不得把注释写到共享的 {@code NullConfigNode.INSTANCE} 上。
     *
     * <p>空值节点的注释只有挂在独立实例上才属于「本文档这个位置」；一旦落到共享单例，
     * 同一进程内之后加载的无注释配置会凭空长出前一份文档的注释（注释归属被跨文档串味）。</p>
     */
    @Test
    public void commentedNullNodesDoNotLeakAcrossDocuments() throws ConfigException {
        ConfigNode first = Config.parse("# 仅此文档的说明\noptional: null # 空值内联\n", ConfigFormat.YAML);
        assertNotNull("本文档的 null 值应带块注释", first.get("optional").getBlockComment());
        assertNotNull("本文档的 null 值应带内联注释", first.get("optional").getInlineComment());

        ConfigNode second = Config.parse("other: null\nplain: 1\n", ConfigFormat.YAML);
        assertNull("另一文档的 null 值不得继承前文块注释", second.get("other").getBlockComment());
        assertNull("另一文档的 null 值不得继承前文内联注释", second.get("other").getInlineComment());
        assertNull("普通标量节点同样不受影响", second.get("plain").getBlockComment());
    }

    @Test
    public void testYamlInlineList() throws ConfigException {
        String yaml = "name: test\n" +
                      "tags:\n" +
                      "  - java\n" +
                      "  - config\n" +
                      "  - yaml";
        ConfigNode node = Config.parse(yaml, ConfigFormat.YAML);

        ConfigNode tags = node.get("tags");
        assertEquals(3, tags.asList().size());
        assertEquals("java", tags.get(0).asString());
        assertEquals("config", tags.get(1).asString());
        assertEquals("yaml", tags.get(2).asString());
    }
}
