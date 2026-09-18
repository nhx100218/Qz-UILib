package club.heiqi.uilib.client.command;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

/**
 * 发布产物内 {@code /qzuilib} 命令的纯 JVM 契约测试。
 *
 * <p>这是玩家在发布产物里唯一触达的入口，故锁三件：用法文案、解析结果、以及开发设施子命令
 * （{@code test}/{@code glass}）必须被拒绝——它们随 {@code internal.devtools} 整包排除出产物，
 * 放行会走到缺类的开屏路径。</p>
 */
public class QzUiLibClientCommandTest {

    @Test
    public void shouldExposeOnlyPlayerFacingSubcommands() {
        QzUiLibClientCommand command = new QzUiLibClientCommand();
        RecordingSender sender = new RecordingSender();

        Assert.assertEquals("qzuilib", command.getCommandName());
        Assert.assertEquals("/qzuilib <modernconfig|chatmd on|off|status>", command.getCommandUsage(sender));
        Assert.assertEquals(0, command.getRequiredPermissionLevel());

        Assert.assertTrue(command.addTabCompletionOptions(sender, new String[] { "mod" }).contains("modernconfig"));
        Assert.assertTrue(command.addTabCompletionOptions(sender, new String[] { "ch" }).contains("chatmd"));
        Assert.assertTrue(command.addTabCompletionOptions(sender, new String[] { "chatmd", "o" }).contains("on"));
        Assert.assertTrue(command.addTabCompletionOptions(sender, new String[] { "chatmd", "" }).contains("status"));
        Assert.assertTrue("开发设施子命令不得出现在生产补全里",
                command.addTabCompletionOptions(sender, new String[] { "te" }).isEmpty());
        Assert.assertTrue("开发设施子命令不得出现在生产补全里",
                command.addTabCompletionOptions(sender, new String[] { "gl" }).isEmpty());
    }

    @Test
    public void resolveSubcommandAcceptsPlayerFacingAndRejectsDevOnly() {
        Assert.assertEquals(QzUiLibClientCommand.Subcommand.MODERN_CONFIG,
                QzUiLibClientCommand.resolveSubcommand(new String[] { "modernconfig" }));
        Assert.assertEquals(QzUiLibClientCommand.Subcommand.MODERN_CONFIG,
                QzUiLibClientCommand.resolveSubcommand(new String[] { "ModernConfig" }));
        Assert.assertEquals(QzUiLibClientCommand.Subcommand.CHATMD_ON,
                QzUiLibClientCommand.resolveSubcommand(new String[] { "chatmd", "on" }));
        Assert.assertEquals(QzUiLibClientCommand.Subcommand.CHATMD_OFF,
                QzUiLibClientCommand.resolveSubcommand(new String[] { "chatmd", "OFF" }));
        Assert.assertEquals(QzUiLibClientCommand.Subcommand.CHATMD_STATUS,
                QzUiLibClientCommand.resolveSubcommand(new String[] { "ChatMd", "status" }));

        Assert.assertNull("test 属开发设施，随 internal.devtools 排除，生产必须拒绝",
                QzUiLibClientCommand.resolveSubcommand(new String[] { "test" }));
        Assert.assertNull("glass 属开发设施，随 internal.devtools 排除，生产必须拒绝",
                QzUiLibClientCommand.resolveSubcommand(new String[] { "glass" }));
        Assert.assertNull(QzUiLibClientCommand.resolveSubcommand(new String[0]));
        Assert.assertNull(QzUiLibClientCommand.resolveSubcommand(new String[] { "inventory" }));
        Assert.assertNull(QzUiLibClientCommand.resolveSubcommand(new String[] { "chatmd" }));
        Assert.assertNull(QzUiLibClientCommand.resolveSubcommand(new String[] { "chatmd", "toggle" }));
        Assert.assertNull(QzUiLibClientCommand.resolveSubcommand(null));
        Assert.assertNull(QzUiLibClientCommand.resolveSubcommand(new String[] { null }));
        Assert.assertNull(QzUiLibClientCommand.resolveSubcommand(new String[] { "chatmd", null }));
    }

    @Test
    public void shouldRejectUnknownArgumentsWithFixedUsage() {
        QzUiLibClientCommand command = new QzUiLibClientCommand();
        RecordingSender sender = new RecordingSender();

        assertUsageRejected(command, sender, new String[0]);
        assertUsageRejected(command, sender, new String[] { "inventory" });
        assertUsageRejected(command, sender, new String[] { "test" });
        assertUsageRejected(command, sender, new String[] { "chatmd", "toggle" });

        Assert.assertTrue(sender.messages.isEmpty());
    }

    @Test
    public void shouldReportChatStatusWithoutTouchingMinecraft() {
        QzUiLibClientCommand command = new QzUiLibClientCommand();
        RecordingSender sender = new RecordingSender();

        command.processCommand(sender, new String[] { "chatmd", "status" });

        Assert.assertEquals(1, sender.messages.size());
        String text = sender.messages.get(0).getUnformattedText();
        Assert.assertTrue("status 报告应含开关字段", text.contains("开关="));
        Assert.assertTrue("status 报告应含接管状态字段", text.contains("接管状态="));
    }

    private static void assertUsageRejected(QzUiLibClientCommand command, RecordingSender sender, String[] args) {
        try {
            command.processCommand(sender, args);
            Assert.fail("Expected WrongUsageException");
        } catch (WrongUsageException expected) {
            Assert.assertEquals("/qzuilib <modernconfig|chatmd on|off|status>", expected.getMessage());
        }
    }

    /**
     * 供测试使用的最小命令发送者。
     */
    private static final class RecordingSender implements ICommandSender {

        private final List<IChatComponent> messages = new ArrayList<IChatComponent>();

        @Override
        public String getCommandSenderName() {
            return "tester";
        }

        @Override
        public IChatComponent func_145748_c_() {
            return new ChatComponentText(getCommandSenderName());
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            messages.add(message);
        }

        @Override
        public boolean canCommandSenderUseCommand(int permissionLevel, String command) {
            return true;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 0, 0);
        }

        @Override
        public World getEntityWorld() {
            return null;
        }
    }
}
