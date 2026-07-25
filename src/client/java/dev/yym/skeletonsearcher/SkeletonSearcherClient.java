package dev.yym.skeletonsearcher;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class SkeletonSearcherClient implements ClientModInitializer {
    public static final String MOD_ID = "skeleton_searcher";
    public static final SolidBlockRenderer RENDERER = new SolidBlockRenderer();

    private static KeyBinding openSettingsKey;
    private static KeyBinding clearAllKey;

    @Override
    public void onInitializeClient() {
        ModConfig.load();

        KeyBinding.Category category = KeyBinding.Category.create(
                Identifier.of(MOD_ID, "general"));
        openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.skeleton_searcher.open_settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                category));
        clearAllKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.skeleton_searcher.clear_all",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_DELETE,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(SkeletonSearcherClient::onEndClientTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RENDERER.close());
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(RENDERER::render);
    }

    private static void onEndClientTick(MinecraftClient client) {
        while (openSettingsKey.wasPressed()) {
            if (client.currentScreen == null) {
                client.setScreen(new SphereSettingsScreen(null));
            }
        }

        while (clearAllKey.wasPressed()) {
            int removed = ModConfig.clearAll();
            RENDERER.clearCache();
            if (client.player != null) {
                String message = removed == 0
                        ? "当前没有已生成的球体"
                        : "已清除全部球体（" + removed + " 个）";
                client.player.sendMessage(Text.literal(message), true);
            }
        }

        RENDERER.tick(client);
    }
}
