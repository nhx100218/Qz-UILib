package club.heiqi.config.runtime;

import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** #76：schema 草稿基线与完整写盘事务分别守卫自己的变更域。 */
public class ConfigRevisionContractTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void overlayAfterOpenIsPreservedAndDoesNotStaleDraft() throws Exception {
        File file = temp.newFile();
        ConfigManager manager = ConfigManager.bootstrap(file, SchemaTestFactory.serverSchema());
        DraftBuffer draft = manager.openDraft();
        manager.authority().legacy().setRawJson("server.extension", "{flag: true}");
        manager.authority().legacy().setRawJson("external", "{value: 42}");
        draft.setDraft("server.host", "user.host");
        assertTrue(manager.save(draft).isSuccess());
        ConfigManager readBack = ConfigManager.bootstrap(file, SchemaTestFactory.serverSchema());
        assertTrue(readBack.authority().legacy().getRawJson("server.extension").contains("true"));
        assertTrue(readBack.authority().legacy().getRawJson("external").contains("42"));
        assertTrue(manager.save(draft).isSuccess());
    }

    @Test public void schemaAbaAndSameValueReloadInvalidateOldDraft() throws Exception {
        ConfigManager manager = ConfigManager.bootstrap(temp.newFile(), SchemaTestFactory.serverSchema());
        DraftBuffer draft = manager.openDraft();
        manager.authority().legacy().setRawJson("server.host", "changed");
        manager.authority().legacy().setRawJson("server.host", "localhost");
        assertEquals(SaveOutcome.ConflictType.STALE_DRAFT_BASE, manager.save(draft).conflictType());
        DraftBuffer beforeReload = manager.openDraft();
        DraftBuffer reloaded = manager.reloadDraftFromDisk();
        assertEquals(SaveOutcome.ConflictType.STALE_DRAFT_BASE, manager.save(beforeReload).conflictType());
        assertTrue(manager.save(reloaded).isSuccess());
    }

    @Test public void sectionOverlayAndLocalCommitCannotForgeSchemaBaseline() throws Exception {
        ConfigManager manager = ConfigManager.bootstrap(temp.newFile(), SchemaTestFactory.serverSchema());
        DraftBuffer draft = manager.openDraft();
        manager.authority().legacy().setRawJson("server", "{extension: keep, host: null}");
        assertTrue("仅 overlay 与 null 不得推进 schema 代数", manager.save(draft).isSuccess());
        assertTrue(manager.authority().legacy().getRawJson("server.extension").contains("keep"));
        manager.authority().legacy().setRawJson("server.host", "localhost");
        draft.commitDraftToCurrent();
        assertEquals("本地 commit 不能重新绑定权威基线", SaveOutcome.ConflictType.STALE_DRAFT_BASE,
                manager.save(draft).conflictType());
    }

    @Test public void schemaAbaDuringSaveAndOverlayAbaDuringReloadRejectCommit() throws Exception {
        for (boolean reload : new boolean[] {false, true}) {
            final ConfigManager[] holder = new ConfigManager[1];
            String path = reload ? "external" : "server.host";
            String baseline = reload ? "42" : "localhost";
            ConfigManager manager = ConfigManager.bootstrap(temp.newFile(), SchemaTestFactory.serverSchema(), view -> {
                try {
                    holder[0].authority().legacy().setRawJson(path, "changed");
                    holder[0].authority().legacy().setRawJson(path, baseline);
                } catch (Exception e) { throw new AssertionError(e); }
                return ValidationResult.ok();
            });
            holder[0] = manager;
            if (reload) {
                manager.authority().legacy().setRawJson(path, baseline);
                try { manager.reloadDraftFromDisk(); fail("准备窗口内 overlay ABA 必须使 reload 冲突"); }
                catch (ConfigReloadException expected) {
                    assertEquals(SaveOutcome.ConflictType.AUTHORITY_MODIFIED_DURING_SAVE, expected.conflictType());
                }
            } else {
                assertEquals(SaveOutcome.ConflictType.AUTHORITY_MODIFIED_DURING_SAVE,
                        manager.save(manager.openDraft()).conflictType());
            }
        }
    }

    @Test public void overlayAbaDuringValidationRejectsPreparedWrite() throws Exception {
        final ConfigManager[] holder = new ConfigManager[1];
        File file = temp.newFile();
        ConfigManager manager = ConfigManager.bootstrap(file, SchemaTestFactory.serverSchema(), view -> {
            try {
                holder[0].authority().legacy().setRawJson("external", "changed");
                holder[0].authority().legacy().setRawJson("external", "42");
            } catch (Exception e) { throw new AssertionError(e); }
            return ValidationResult.ok();
        });
        holder[0] = manager;
        manager.authority().legacy().setRawJson("external", "42");
        byte[] before = Files.readAllBytes(file.toPath());
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("server.host", "user.host");
        assertEquals(SaveOutcome.ConflictType.AUTHORITY_MODIFIED_DURING_SAVE, manager.save(draft).conflictType());
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
        assertEquals("user.host", draft.getDraft("server.host"));
    }
}
