package club.heiqi.config.runtime;

import club.heiqi.config.ConfigException;
import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.SectionSpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** #76：旧键必须在打开草稿之前归一，加载与 reload 使用同一选择规则。 */
public class ConfigLegacyAliasTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private ConfigSchema schema() {
        return ConfigSchema.builder("aliases").section("size")
                .number("current").defaultValue(9.0).legacyAliases("old", "older").build()
                .endSection().build();
    }
    private File file(String text) throws Exception {
        File file = temp.newFile();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test public void loadAndReloadNormalizeWithoutWritingAndSaveRemovesOnlyAliases() throws Exception {
        File file = file("size:\n  old: 12\n  older: 15\n  extension: keep\nunknown: keep\n");
        byte[] original = Files.readAllBytes(file.toPath());
        ConfigManager manager = ConfigManager.bootstrap(file, schema());
        assertEquals(12.0, manager.authority().getNumber("size.current"), 0.0);
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        assertEquals("", manager.authority().legacy().getRawJson("size.old"));
        DraftBuffer draft = manager.reloadDraftFromDisk();
        assertEquals(12.0, ((Number) draft.getDraft("size.current")).doubleValue(), 0.0);
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        assertTrue(manager.save(draft).isSuccess());
        String saved = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertFalse(saved.contains("old:"));
        assertFalse(saved.contains("older:"));
        assertTrue(saved.contains("current:"));
        assertTrue(saved.contains("extension:"));
        assertTrue(saved.contains("unknown:"));
    }

    @Test public void explicitNewKeyIncludingNullWinsOverInvalidAlias() throws Exception {
        for (String current : Arrays.asList("18", "null")) {
            File file = file("size:\n  current: " + current + "\n  old: broken\n");
            ConfigManager manager = ConfigManager.bootstrap(file, schema());
            double expected = current.equals("null") ? 9.0 : 18.0;
            assertEquals(expected, manager.authority().getNumber("size.current"), 0.0);
            assertEquals(expected, ((Number) manager.reloadDraftFromDisk().getDraft("size.current")).doubleValue(), 0.0);
        }
    }

    @Test public void invalidSelectedAliasFailsLoadAndReloadWithoutMutatingAuthority() throws Exception {
        File bad = file("size:\n  old: broken\n");
        try { ConfigManager.bootstrap(bad, schema()); fail("非法旧键应与非法新键同样拒绝"); }
        catch (ConfigException expected) { }
        File file = file("size:\n  current: 12\n");
        ConfigManager manager = ConfigManager.bootstrap(file, schema());
        Files.write(file.toPath(), Files.readAllBytes(bad.toPath()));
        long before = manager.authority().revision();
        try { manager.reloadDraftFromDisk(); fail("非法旧键不得提交 reload"); }
        catch (ConfigException expected) { }
        assertEquals(before, manager.authority().revision());
        assertEquals(12.0, manager.authority().getNumber("size.current"), 0.0);
    }

    @Test public void firstPresentAliasIncludingNullWinsAndMetadataIsImmutable() throws Exception {
        ConfigManager manager = ConfigManager.bootstrap(file("size:\n  old: null\n  older: 15\n"), schema());
        assertEquals(9.0, manager.authority().getNumber("size.current"), 0.0);
        assertEquals(9.0, ((Number) manager.reloadDraftFromDisk().getDraft("size.current")).doubleValue(), 0.0);
        FieldSpec source = schema().field("size.current");
        List<String> aliases = new ArrayList<String>(source.legacyAliases());
        FieldSpec copy = new FieldSpec(source.path(), source.type(), source.defaultValue(), source.constraints(),
                source.label(), source.helper(), source.widget(), source.valueSpec(), aliases);
        aliases.clear();
        assertEquals(Arrays.asList("old", "older"), copy.legacyAliases());
        try { copy.legacyAliases().clear(); fail("旧键元数据必须不可变"); }
        catch (UnsupportedOperationException expected) { }
        try {
            new ConfigSchema("bad", null, Collections.singletonList(
                    new SectionSpec("other", null, Collections.singletonList(copy))));
            fail("旧键字段不能挂在另一 section 下");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void aliasOwnershipAndDeclarationAreUnambiguous() {
        for (String alias : Arrays.asList("current", "nested.key", "", "old[0]")) {
            try {
                ConfigSchema.builder("bad").section("size").number("current")
                        .legacyAliases(alias).build().endSection().build();
                fail("拒绝歧义旧键: " + alias);
            } catch (IllegalArgumentException expected) { }
        }
        try {
            ConfigSchema.builder("bad").section("size").number("first").legacyAliases("old").build()
                    .number("second").legacyAliases("old").build().endSection().build();
            fail("同一旧键不能属于两个字段");
        } catch (IllegalArgumentException expected) { }
    }
}
