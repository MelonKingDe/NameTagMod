package de.example.nametaglines;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import de.example.nametaglines.NametagConfig.LineData;
import de.example.nametaglines.gui.NametagEditorScreen;
import de.example.nametaglines.render.NametagRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands; // may be ClientCommandManager (see README)
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class NametagLinesClient implements ClientModInitializer {
    public static final String MOD_ID = "nametaglines";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        NametagConfig.load();

        // Load icons only once the client (and the GPU) is ready
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> IconManager.reload());

        // Draw the nametag lines as a HUD overlay so nothing in the world can cover them
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "nametag_lines"),
                (graphics, tracker) -> NametagRenderer.draw(graphics));

        // Key to open the editor (default: K, changeable under Options > Controls)
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        KeyMapping openEditor = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.nametaglines.open_editor", InputConstants.Type.KEYSYM, InputConstants.KEY_K, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openEditor.consumeClick()) {
                client.gui.setScreen(new NametagEditorScreen(client.gui.screen()));
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("nametag")
                        .then(sideNode("above", true))
                        .then(sideNode("below", false))
                        .then(ClientCommands.literal("gui").executes(ctx -> {
                            // open delayed, otherwise closing the chat overwrites the screen
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> mc.gui.setScreen(new NametagEditorScreen(null)));
                            return 1;
                        }))
                        .then(ClientCommands.literal("list").executes(this::list))
                        .then(ClientCommands.literal("icons")
                                .executes(ctx -> {
                                    msg(ctx, "Folder: " + IconManager.folder());
                                    for (IconManager.Icon i : IconManager.all()) msg(ctx, "{icon:" + i.name() + "}");
                                    return 1;
                                })
                                .then(ClientCommands.literal("reload").executes(ctx -> {
                                    IconManager.reload();
                                    return msg(ctx, IconManager.all().size() + " icon(s) loaded.");
                                })))
                        .then(ClientCommands.literal("collection")
                                // /nametag collection test <name>  -> shows raw API responses for debugging
                                .then(ClientCommands.literal("test")
                                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    Minecraft mc = Minecraft.getInstance();
                                                    FabricClientCommandSource source = ctx.getSource();
                                                    CollectionService.test(StringArgumentType.getString(ctx, "name"),
                                                            line -> mc.execute(() -> source.sendFeedback(Component.literal(line))));
                                                    return msg(ctx, "Testing...");
                                                })))
                                // /nametag collection profile <id|auto>
                                .then(ClientCommands.literal("profile")
                                        .then(ClientCommands.argument("id", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String id = StringArgumentType.getString(ctx, "id");
                                                    NametagConfig.get().collectionProfileId = id.equalsIgnoreCase("auto") ? "" : id;
                                                    NametagConfig.save();
                                                    CollectionService.refreshAll();
                                                    return msg(ctx, id.equalsIgnoreCase("auto")
                                                            ? "Using the selected profile automatically."
                                                            : "Profile set to " + id + ".");
                                                })))
                                // /nametag collection refresh
                                .then(ClientCommands.literal("refresh").executes(ctx -> {
                                    CollectionService.refreshAll();
                                    return msg(ctx, "Collection data will be fetched again.");
                                })))
                        .then(ClientCommands.literal("social")
                                // /nametag social test <youtube|twitch|tiktok> <name>
                                .then(ClientCommands.literal("test")
                                        .then(ClientCommands.argument("platform", StringArgumentType.word())
                                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                                        .executes(ctx -> {
                                                            Minecraft mc = Minecraft.getInstance();
                                                            FabricClientCommandSource source = ctx.getSource();
                                                            SocialService.test(StringArgumentType.getString(ctx, "platform"),
                                                                    StringArgumentType.getString(ctx, "name"),
                                                                    line -> mc.execute(() -> source.sendFeedback(Component.literal(line))));
                                                            return msg(ctx, "Testing...");
                                                        }))))
                                .then(ClientCommands.literal("refresh").executes(ctx -> {
                                    SocialService.refreshAll();
                                    return msg(ctx, "Social counts will be fetched again.");
                                })))
                        .then(ClientCommands.literal("others")
                                .then(ClientCommands.literal("on").executes(ctx -> {
                                    NametagConfig.get().othersEnabled = true;
                                    NametagConfig.save();
                                    return msg(ctx, "Line above other players: on");
                                }))
                                .then(ClientCommands.literal("off").executes(ctx -> {
                                    NametagConfig.get().othersEnabled = false;
                                    NametagConfig.save();
                                    return msg(ctx, "Line above other players: off");
                                }))
                                .then(ClientCommands.literal("text")
                                        .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    NametagConfig.get().othersLine.text = StringArgumentType.getString(ctx, "text");
                                                    NametagConfig.save();
                                                    return msg(ctx, "Line for other players updated.");
                                                }))))
                        .then(ClientCommands.literal("mode")
                                .then(ClientCommands.literal("nametag").executes(ctx -> setMode(ctx, true)))
                                .then(ClientCommands.literal("overlay").executes(ctx -> setMode(ctx, false))))
                        .then(ClientCommands.literal("toggle").executes(ctx -> {
                            NametagConfig c = NametagConfig.get();
                            c.enabled = !c.enabled;
                            NametagConfig.save();
                            return msg(ctx, "Extra lines: " + (c.enabled ? "on" : "off"));
                        }))
                        .then(ClientCommands.literal("ownname").executes(ctx -> {
                            NametagConfig c = NametagConfig.get();
                            c.showOwnName = !c.showOwnName;
                            NametagConfig.save();
                            return msg(ctx, "Show own name: " + (c.showOwnName ? "on" : "off"));
                        }))));
        LOGGER.info("NameTagMod loaded");
    }

    private static List<LineData> lines(boolean above) {
        return above ? NametagConfig.get().top : NametagConfig.get().bottom;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> sideNode(String name, boolean above) {
        return ClientCommands.literal(name)
                // /nametag above add <text>
                .then(ClientCommands.literal("add")
                        .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    if (lines(above).size() >= NametagConfig.MAX_LINES) {
                                        return msg(ctx, "Maximum of " + NametagConfig.MAX_LINES + " lines reached.");
                                    }
                                    lines(above).add(new LineData(StringArgumentType.getString(ctx, "text")));
                                    NametagConfig.save();
                                    return msg(ctx, "Line added (" + name + ").");
                                })))
                // /nametag above set <nr> <text>
                .then(ClientCommands.literal("set")
                        .then(ClientCommands.argument("nr", IntegerArgumentType.integer(1))
                                .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            int i = IntegerArgumentType.getInteger(ctx, "nr") - 1;
                                            List<LineData> l = lines(above);
                                            if (i >= l.size()) return msg(ctx, "Line " + (i + 1) + " does not exist.");
                                            l.get(i).text = StringArgumentType.getString(ctx, "text");
                                            NametagConfig.save();
                                            return msg(ctx, "Line " + (i + 1) + " (" + name + ") updated.");
                                        }))))
                // /nametag above scale <nr> <factor>
                .then(ClientCommands.literal("scale")
                        .then(ClientCommands.argument("nr", IntegerArgumentType.integer(1))
                                .then(ClientCommands.argument("factor", FloatArgumentType.floatArg(
                                                NametagConfig.MIN_SCALE, NametagConfig.MAX_SCALE))
                                        .executes(ctx -> {
                                            int i = IntegerArgumentType.getInteger(ctx, "nr") - 1;
                                            List<LineData> l = lines(above);
                                            if (i >= l.size()) return msg(ctx, "Line " + (i + 1) + " does not exist.");
                                            l.get(i).scale = FloatArgumentType.getFloat(ctx, "factor");
                                            NametagConfig.save();
                                            return msg(ctx, "Scale of line " + (i + 1) + " (" + name + ") updated.");
                                        }))))
                // /nametag above offset <nr> <pixels>   (positive = up, negative = down)
                .then(ClientCommands.literal("offset")
                        .then(ClientCommands.argument("nr", IntegerArgumentType.integer(1))
                                .then(ClientCommands.argument("pixels", FloatArgumentType.floatArg(-100f, 100f))
                                        .executes(ctx -> {
                                            int i = IntegerArgumentType.getInteger(ctx, "nr") - 1;
                                            List<LineData> l = lines(above);
                                            if (i >= l.size()) return msg(ctx, "Line " + (i + 1) + " does not exist.");
                                            l.get(i).offsetY = FloatArgumentType.getFloat(ctx, "pixels");
                                            NametagConfig.save();
                                            return msg(ctx, "Shift of line " + (i + 1) + " (" + name + ") updated.");
                                        }))))
                // /nametag above remove <nr>
                .then(ClientCommands.literal("remove")
                        .then(ClientCommands.argument("nr", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    int i = IntegerArgumentType.getInteger(ctx, "nr") - 1;
                                    List<LineData> l = lines(above);
                                    if (i >= l.size()) return msg(ctx, "Line " + (i + 1) + " does not exist.");
                                    l.remove(i);
                                    NametagConfig.save();
                                    return msg(ctx, "Line " + (i + 1) + " (" + name + ") removed.");
                                })))
                // /nametag above clear
                .then(ClientCommands.literal("clear").executes(ctx -> {
                    lines(above).clear();
                    NametagConfig.save();
                    return msg(ctx, "All lines (" + name + ") cleared.");
                }));
    }

    private static int setMode(CommandContext<FabricClientCommandSource> ctx, boolean nametag) {
        NametagConfig.get().attachToNametag = nametag;
        NametagConfig.save();
        return msg(ctx, nametag
                ? "Lines are attached to the nametag (follow all view effects)."
                : "Lines are drawn as a screen overlay (always fully visible).");
    }

    private int list(CommandContext<FabricClientCommandSource> ctx) {
        NametagConfig c = NametagConfig.get();
        msg(ctx, "--- above the name ---");
        for (int i = 0; i < c.top.size(); i++) {
            msg(ctx, (i + 1) + ": " + c.top.get(i).text + "  (" + c.top.get(i).scale + "x)");
        }
        msg(ctx, "--- below the name ---");
        for (int i = 0; i < c.bottom.size(); i++) {
            msg(ctx, (i + 1) + ": " + c.bottom.get(i).text + "  (" + c.bottom.get(i).scale + "x)");
        }
        return 1;
    }

    private static int msg(CommandContext<FabricClientCommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(Component.literal(text));
        return 1;
    }
}
