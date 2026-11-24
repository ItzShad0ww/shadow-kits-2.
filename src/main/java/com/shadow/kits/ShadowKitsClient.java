package com.shadow.kits;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.serialization.JsonOps;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryOps;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ShadowKitsClient implements ClientModInitializer {
    // Inventory indexes: 0-35 main (0-8 hotbar), 36-39 armor (feet..head), 40 offhand.
    private static final int SIZE = 41;

    private static final SuggestionProvider<FabricClientCommandSource> NAMES =
            (ctx, builder) -> CommandSource.suggestMatching(KitStorage.names(), builder);

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("kit")
                        .then(literal("save").then(argument("name", StringArgumentType.word())
                                .executes(c -> save(c, StringArgumentType.getString(c, "name")))))
                        .then(literal("load").then(argument("name", StringArgumentType.word())
                                .suggests(NAMES)
                                .executes(c -> load(c, StringArgumentType.getString(c, "name")))))
                        .then(literal("delete").then(argument("name", StringArgumentType.word())
                                .suggests(NAMES)
                                .executes(c -> delete(c, StringArgumentType.getString(c, "name")))))
                        .then(literal("list").executes(this::list))));
    }

    private static void msg(CommandContext<FabricClientCommandSource> c, String text, Formatting color) {
        c.getSource().sendFeedback(Text.literal("[Kit] " + text).formatted(color));
    }

    private int save(CommandContext<FabricClientCommandSource> c, String name) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return 0;
        RegistryOps<JsonElement> ops = p.getRegistryManager().getOps(JsonOps.INSTANCE);
        PlayerInventory inv = p.getInventory();
        JsonArray arr = new JsonArray();
        for (int i = 0; i < SIZE; i++) {
            arr.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, inv.getStack(i)).result()
                    .orElse(new com.google.gson.JsonObject()));
        }
        if (KitStorage.put(name, arr)) {
            msg(c, "Saved kit '" + name + "'.", Formatting.AQUA);
            return 1;
        }
        msg(c, "Could not write the kit file.", Formatting.RED);
        return 0;
    }

    private int load(CommandContext<FabricClientCommandSource> c, String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null) return 0;

        JsonArray arr = KitStorage.get(name);
        if (arr == null || arr.size() < SIZE) {
            msg(c, "No kit named '" + name + "'.", Formatting.RED);
            return 0;
        }
        if (p.currentScreenHandler != p.playerScreenHandler
                || !p.playerScreenHandler.getCursorStack().isEmpty()) {
            msg(c, "Close any container and empty your cursor first.", Formatting.RED);
            return 0;
        }

        RegistryOps<JsonElement> ops = p.getRegistryManager().getOps(JsonOps.INSTANCE);
        ItemStack[] target = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) {
            target[i] = ItemStack.OPTIONAL_CODEC.parse(ops, arr.get(i)).result().orElse(ItemStack.EMPTY);
        }

        PlayerInventory inv = p.getInventory();
        boolean[] done = new boolean[SIZE];
        for (int i = 0; i < SIZE; i++) {
            if (!target[i].isEmpty() && matches(inv.getStack(i), target[i], true)) done[i] = true;
        }

        int moved = 0, missing = 0;
        for (int i = 0; i < SIZE; i++) {
            if (done[i] || target[i].isEmpty()) continue;
            int src = find(inv, target[i], done, i, true);
            if (src < 0) src = find(inv, target[i], done, i, false);
            if (src < 0) {
                missing++;
                continue;
            }
            swap(mc, p, src, i);
            done[i] = true;
            moved++;
        }

        msg(c, "Loaded '" + name + "': " + moved + " moved"
                + (missing > 0 ? ", " + missing + " missing from your inventory." : "."),
                missing > 0 ? Formatting.YELLOW : Formatting.AQUA);
        return 1;
    }

    private int delete(CommandContext<FabricClientCommandSource> c, String name) {
        if (KitStorage.remove(name)) {
            msg(c, "Deleted kit '" + name + "'.", Formatting.AQUA);
            return 1;
        }
        msg(c, "No kit named '" + name + "'.", Formatting.RED);
        return 0;
    }

    private int list(CommandContext<FabricClientCommandSource> c) {
        var names = KitStorage.names();
        msg(c, names.isEmpty() ? "No kits saved yet. Use /kit save <name>."
                : "Kits: " + String.join(", ", names), Formatting.AQUA);
        return 1;
    }

    private static boolean matches(ItemStack a, ItemStack b, boolean exactCount) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (!ItemStack.areItemsAndComponentsEqual(a, b)) return false;
        return !exactCount || a.getCount() == b.getCount();
    }

    /** Finds an unlocked slot holding the wanted item. Armor slots are never used as a source. */
    private static int find(PlayerInventory inv, ItemStack want, boolean[] done, int self, boolean exactCount) {
        for (int j = 0; j < SIZE; j++) {
            if (j == self || done[j] || (j >= 36 && j < 40)) continue;
            if (matches(inv.getStack(j), want, exactCount)) return j;
        }
        return -1;
    }

    private static int screenSlot(int inv) {
        if (inv < 9) return 36 + inv;        // hotbar
        if (inv < 36) return inv;            // main
        if (inv < 40) return 8 - (inv - 36); // feet=8, legs=7, chest=6, head=5
        return 45;                           // offhand
    }

    private static void swap(MinecraftClient mc, ClientPlayerEntity p, int a, int b) {
        int id = p.playerScreenHandler.syncId;
        mc.interactionManager.clickSlot(id, screenSlot(a), 0, SlotActionType.PICKUP, p);
        mc.interactionManager.clickSlot(id, screenSlot(b), 0, SlotActionType.PICKUP, p);
        mc.interactionManager.clickSlot(id, screenSlot(a), 0, SlotActionType.PICKUP, p);
    }
            }
