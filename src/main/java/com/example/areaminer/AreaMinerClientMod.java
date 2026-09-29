package com.example.areaminer;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * AreaMiner (клиентский мод).
 *
 * 1. Берёте в руку предмет-палочку (по умолчанию деревянная мотыга).
 *    ЛКМ по блоку — точка 1, ПКМ по блоку — точка 2 (как в WorldEdit).
 *    Пока обе точки заданы, контур области подсвечивается частицами.
 * 2. Запускаете: команда /areaminer start или назначенная кнопка (Управление -> AreaMiner).
 * 3. Персонаж идёт по области "змейкой" ПО СТОЛБЦАМ: в каждой точке сразу выкапывает
 *    весь столбец сверху донизу, стоя на месте (без прыжков), затем делает шаг вперёд.
 *    Если под следующей клеткой пустота — сам подставляет блок-мостик из хотбара.
 *    Когда в инвентаре кончается место — внизу экрана пишет об этом и ставит на паузу.
 *
 * Работает только через обычные действия игрока (движение, взгляд, удержание кнопки атаки),
 * поэтому на сервере ничего ставить не нужно.
 */
public class AreaMinerClientMod implements ClientModInitializer {

    public static final String MOD_ID = "areaminer";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int MAX_COLUMNS = 10_000;
    private static final int MAX_CELL_TICKS = 600;
    private static final float MIN_HEALTH = 8.0F;
    private static final int FULL_MESSAGE_INTERVAL = 40;
    private static final int PARTICLE_INTERVAL = 10;

    /** Блоки, которыми мод будет застраивать дыры под ногами, по приоритету. */
    private static final Item[] FILLER_PRIORITY = {
            Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.STONE
    };

    private enum Phase { APPROACH, ADVANCE }

    private KeyMapping toggleKey;

    // ---- выделение ----
    private Item wandItem = Items.WOODEN_HOE;
    private BlockPos pos1;
    private BlockPos pos2;
    private int particleCooldown;

    // ---- состояние работы ----
    private boolean working = false;
    private Phase phase = Phase.APPROACH;
    private int minX, maxX, minY, maxY, minZ, maxZ;
    private final List<int[]> columns = new ArrayList<>();
    private int index;
    /** Y блока, который сейчас выкапывается в текущем столбце (идём сверху вниз). */
    private int clearY;
    private int cellTicks;
    private int fullMessageCooldown;
    private boolean fullNotified;
    private int originalSlot = -1;
    private BlockPos lastToolPos;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.areaminer.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, // по умолчанию не назначена — назначьте в Управлении
                category
        ));

        registerSelectionCallbacks();
        registerCommands();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        LOGGER.info("AreaMiner (клиент) загружен");
    }

    // =====================================================================
    //  Выделение области предметом-палочкой + подсветка частицами
    // =====================================================================

    private void registerSelectionCallbacks() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!isWandClick(player, level, hand)) {
                return InteractionResult.PASS;
            }
            setPoint(1, pos);
            return InteractionResult.FAIL;
        });

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!isWandClick(player, level, hand)) {
                return InteractionResult.PASS;
            }
            setPoint(2, hitResult.getBlockPos());
            return InteractionResult.FAIL;
        });
    }

    private boolean isWandClick(Player player, Level level, InteractionHand hand) {
        return level.isClientSide()
                && !working
                && hand == InteractionHand.MAIN_HAND
                && player.getMainHandItem().is(wandItem);
    }

    private void setPoint(int which, BlockPos pos) {
        BlockPos p = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
        BlockPos current = (which == 1) ? pos1 : pos2;
        if (p.equals(current)) {
            return;
        }
        if (which == 1) {
            pos1 = p;
        } else {
            pos2 = p;
        }

        String text = "§aТочка " + which + ": " + p.getX() + " " + p.getY() + " " + p.getZ();
        if (pos1 != null && pos2 != null) {
            text += " §7| " + describeRegion();
        }
        showOverlay(text);
    }

    private String describeRegion() {
        int dx = Math.abs(pos1.getX() - pos2.getX()) + 1;
        int dy = Math.abs(pos1.getY() - pos2.getY()) + 1;
        int dz = Math.abs(pos1.getZ() - pos2.getZ()) + 1;
        return "Область " + dx + "×" + dy + "×" + dz + " (" + ((long) dx * dy * dz) + " блоков)";
    }

    /** Рисует частицами контур выделенной области (пока заданы обе точки). */
    private void spawnSelectionParticles(Minecraft mc) {
        if (pos1 == null || pos2 == null || mc.level == null) {
            return;
        }

        double x0 = Math.min(pos1.getX(), pos2.getX());
        double x1 = Math.max(pos1.getX(), pos2.getX()) + 1.0;
        double y0 = Math.min(pos1.getY(), pos2.getY());
        double y1 = Math.max(pos1.getY(), pos2.getY()) + 1.0;
        double z0 = Math.min(pos1.getZ(), pos2.getZ());
        double z1 = Math.max(pos1.getZ(), pos2.getZ()) + 1.0;

        double[][] c = {
                {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1},
                {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}
        };
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };

        for (int[] e : edges) {
            double[] a = c[e[0]];
            double[] b = c[e[1]];
            double len = Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2]));
            int steps = Math.max(1, (int) Math.round(len));
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                mc.level.addParticle(ParticleTypes.END_ROD,
                        a[0] + (b[0] - a[0]) * t,
                        a[1] + (b[1] - a[1]) * t,
                        a[2] + (b[2] - a[2]) * t,
                        0, 0, 0);
            }
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    // =====================================================================
    //  Команды
    // =====================================================================

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
            dispatcher.register(ClientCommands.literal("areaminer")
                .then(ClientCommands.literal("start").executes(ctx -> {
                    startMining(ctx.getSource().getClient());
                    return 1;
                }))
                .then(ClientCommands.literal("stop").executes(ctx -> {
                    stopMining(ctx.getSource().getClient(), "§eАвтокопание остановлено");
                    return 1;
                }))
                .then(ClientCommands.literal("clear").executes(ctx -> {
                    pos1 = null;
                    pos2 = null;
                    ctx.getSource().sendFeedback(Component.literal("Выделение сброшено"));
                    return 1;
                }))
                .then(ClientCommands.literal("wand").executes(ctx -> {
                    ItemStack held = ctx.getSource().getPlayer().getMainHandItem();
                    if (held.isEmpty()) {
                        ctx.getSource().sendError(Component.literal("Возьмите в руку предмет, который станет палочкой выделения"));
                        return 0;
                    }
                    wandItem = held.getItem();
                    ctx.getSource().sendFeedback(Component.literal("Палочка выделения теперь — предмет в вашей руке"));
                    return 1;
                }))
                .then(ClientCommands.literal("status").executes(ctx -> {
                    String p1 = pos1 == null ? "не задана" : pos1.getX() + " " + pos1.getY() + " " + pos1.getZ();
                    String p2 = pos2 == null ? "не задана" : pos2.getX() + " " + pos2.getY() + " " + pos2.getZ();
                    ctx.getSource().sendFeedback(Component.literal(
                            "Точка 1: " + p1 + " | Точка 2: " + p2
                                    + " | " + (working ? "§aработает" : "§eостановлено")));
                    return 1;
                }))
            )
        );
    }

    // =====================================================================
    //  Запуск / остановка
    // =====================================================================

    private void startMining(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        if (working) {
            showOverlay("§eАвтокопание уже работает");
            return;
        }
        if (pos1 == null || pos2 == null) {
            player.sendSystemMessage(Component.literal(
                    "§cСначала выделите область: ЛКМ по блоку — точка 1, ПКМ по блоку — точка 2 (палочкой выделения)."));
            return;
        }

        minX = Math.min(pos1.getX(), pos2.getX());
        maxX = Math.max(pos1.getX(), pos2.getX());
        minY = Math.min(pos1.getY(), pos2.getY());
        maxY = Math.max(pos1.getY(), pos2.getY());
        minZ = Math.min(pos1.getZ(), pos2.getZ());
        maxZ = Math.max(pos1.getZ(), pos2.getZ());

        long area = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        if (area > MAX_COLUMNS) {
            player.sendSystemMessage(Component.literal(
                    "§cОбласть слишком большая (максимум " + MAX_COLUMNS + " клеток по площади)."));
            return;
        }

        BlockPos feet = player.blockPosition();
        int startX = Math.abs(feet.getX() - minX) <= Math.abs(feet.getX() - maxX) ? minX : maxX;
        int startZ = Math.abs(feet.getZ() - minZ) <= Math.abs(feet.getZ() - maxZ) ? minZ : maxZ;
        buildColumns(startX, startZ);

        index = 0;
        clearY = maxY;
        phase = Phase.APPROACH;
        cellTicks = 0;
        fullMessageCooldown = 0;
        fullNotified = false;
        lastToolPos = null;
        originalSlot = player.getInventory().getSelectedSlot();
        working = true;

        showOverlay("§aАвтокопание запущено");
    }

    private void stopMining(Minecraft mc, String message) {
        working = false;
        releaseKeys(mc);
        if (mc.player != null) {
            if (originalSlot >= 0) {
                mc.player.getInventory().setSelectedSlot(originalSlot);
            }
            mc.player.sendSystemMessage(Component.literal(message));
        }
        originalSlot = -1;
    }

    /** Строит порядок обхода столбцов "змейкой", начиная с угла (startX, startZ). */
    private void buildColumns(int startX, int startZ) {
        columns.clear();
        int otherX = (startX == minX) ? maxX : minX;
        int endZ = (startZ == minZ) ? maxZ : minZ;
        int stepZ = endZ >= startZ ? 1 : -1;

        boolean forward = true;
        for (int z = startZ; ; z += stepZ) {
            int fromX = forward ? startX : otherX;
            int toX = forward ? otherX : startX;
            int stepX = toX >= fromX ? 1 : -1;
            for (int x = fromX; ; x += stepX) {
                columns.add(new int[]{x, z});
                if (x == toX) {
                    break;
                }
            }
            forward = !forward;
            if (z == endZ) {
                break;
            }
        }
    }

    // =====================================================================
    //  Главный цикл
    // =====================================================================

    private void onClientTick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            if (working) {
                stopMining(mc, "§eАвтокопание остановлено");
            } else {
                startMining(mc);
            }
        }

        if (particleCooldown-- <= 0) {
            spawnSelectionParticles(mc);
            particleCooldown = PARTICLE_INTERVAL;
        }

        if (working) {
            tickMiner(mc);
        }
    }

    private void tickMiner(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || mc.gameMode == null) {
            working = false;
            return;
        }

        if (mc.screen != null) {
            releaseKeys(mc);
            return;
        }

        if (player.getHealth() < MIN_HEALTH) {
            stopMining(mc, "§cАвтокопание остановлено: мало здоровья");
            return;
        }

        if (isInventoryFull(player)) {
            releaseKeys(mc);
            if (!fullNotified) {
                player.sendSystemMessage(Component.literal(
                        "§cМесто в инвентаре закончилось! Освободите слоты — копание продолжится само."));
                fullNotified = true;
            }
            if (fullMessageCooldown-- <= 0) {
                showOverlay("§c§lМесто в инвентаре закончилось!");
                fullMessageCooldown = FULL_MESSAGE_INTERVAL;
            }
            return;
        }
        fullNotified = false;
        fullMessageCooldown = 0;

        if (++cellTicks > MAX_CELL_TICKS) {
            stopMining(mc, "§cАвтокопание остановлено: персонаж застрял (не может дойти или сломать блок)");
            return;
        }

        if (phase == Phase.APPROACH) {
            tickApproach(mc, player);
        } else {
            tickAdvance(mc, player, level);
        }
    }

    /** Подходим к стартовому столбцу области (по горизонтали, на текущей высоте). */
    private void tickApproach(Minecraft mc, LocalPlayer player) {
        int[] c = columns.get(index);
        double tx = c[0] + 0.5;
        double tz = c[1] + 0.5;
        if (isCentered(player, tx, tz)) {
            releaseKeys(mc);
            phase = Phase.ADVANCE;
            clearY = maxY;
            cellTicks = 0;
            return;
        }
        walkToward(mc, player, tx, tz);
    }

    /**
     * Копаем текущий столбец полностью сверху вниз (стоя на месте), затем заходим в него,
     * затем переходим к следующему столбцу змейки.
     */
    private void tickAdvance(Minecraft mc, LocalPlayer player, Level level) {
        if (index >= columns.size()) {
            stopMining(mc, "§aГотово! Область полностью обработана.");
            return;
        }

        int[] c = columns.get(index);

        // Шаг 1: выкапываем столбец сверху вниз, пока не дойдём до дна области
        if (clearY >= minY) {
            BlockPos target = new BlockPos(c[0], clearY, c[1]);

            if (lavaNear(level, target)) {
                stopMining(mc, "§cАвтокопание остановлено: обнаружена лава!");
                return;
            }
            if (needsDig(level, target)) {
                digAt(mc, player, level, target);
                return;
            }
            clearY--;
            cellTicks = 0;
            return;
        }

        // Шаг 2: столбец полностью пуст. Проверяем, что под ним есть пол, иначе строим мостик
        BlockPos floorPos = new BlockPos(c[0], minY - 1, c[1]);
        BlockState floor = level.getBlockState(floorPos);
        if (floor.isAir() || !floor.getFluidState().isEmpty()) {
            setKey(mc.options.keyUp, false);
            if (tryFillFloor(mc, player, floorPos)) {
                cellTicks = 0;
            } else {
                stopMining(mc, "§cАвтокопание остановлено: под областью пустота, а в хотбаре нет блоков для мостика "
                        + "(положите cobblestone/dirt/netherrack/stone).");
            }
            return;
        }

        // Шаг 3: заходим в очищенный столбец
        double tx = c[0] + 0.5;
        double tz = c[1] + 0.5;
        if (isCentered(player, tx, tz) && player.onGround()) {
            releaseKeys(mc);
            index++;
            clearY = maxY;
            cellTicks = 0;
            lastToolPos = null;
            return;
        }
        walkToward(mc, player, tx, tz);
    }

    // =====================================================================
    //  Действия персонажа
    // =====================================================================

    private void walkToward(Minecraft mc, LocalPlayer player, double tx, double tz) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(tx, player.getEyeY(), tz));
        setKey(mc.options.keyAttack, false);
        setKey(mc.options.keyUp, true);
    }

    private void digAt(Minecraft mc, LocalPlayer player, Level level, BlockPos target) {
        setKey(mc.options.keyUp, false);

        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(target));

        boolean aimed = false;
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = blockHit.getBlockPos();
            if (hitPos.equals(target) && needsDig(level, hitPos)) {
                aimed = true;
                selectToolFor(player, level, hitPos);
            }
        }
        setKey(mc.options.keyAttack, aimed);
    }

    /**
     * Ставит блок-мостик под указанной пустой клеткой пола, "приклеиваясь" к грани блока,
     * на котором персонаж сейчас стоит — как обычная ручная застройка провала при ходьбе.
     */
    private boolean tryFillFloor(Minecraft mc, LocalPlayer player, BlockPos floorPos) {
        BlockPos supportPos = player.blockPosition().below();
        BlockState support = mc.level.getBlockState(supportPos);
        if (support.isAir() || !support.getFluidState().isEmpty()) {
            return false; // сами стоим не на твёрдом — некуда "приклеить" блок
        }

        int dx = Integer.signum(floorPos.getX() - supportPos.getX());
        int dz = Integer.signum(floorPos.getZ() - supportPos.getZ());
        Direction dir = horizontalDirection(dx, dz);
        if (dir == null || !supportPos.relative(dir).equals(floorPos)) {
            return false; // цель не примыкает напрямую — не рискуем строить не туда
        }

        int slot = findFillerSlot(player);
        if (slot < 0) {
            return false;
        }

        Vec3 center = Vec3.atCenterOf(supportPos);
        Vec3 hitVec = center.add(dir.getStepX() * 0.5, dir.getStepY() * 0.5, dir.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, dir, supportPos, false);

        Inventory inventory = player.getInventory();
        int original = inventory.getSelectedSlot();
        boolean needSwap = slot != original;
        if (needSwap) {
            inventory.setSelectedSlot(slot);
        }

        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);

        if (needSwap) {
            inventory.setSelectedSlot(original);
        }
        return true;
    }

    private int findFillerSlot(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (Item preferred : FILLER_PRIORITY) {
            for (int i = 0; i < 9; i++) {
                if (inventory.getItem(i).is(preferred)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static Direction horizontalDirection(int dx, int dz) {
        if (dx > 0) return Direction.EAST;
        if (dx < 0) return Direction.WEST;
        if (dz > 0) return Direction.SOUTH;
        if (dz < 0) return Direction.NORTH;
        return null;
    }

    /** Выбирает из хотбара самый быстрый инструмент для блока. */
    private void selectToolFor(LocalPlayer player, Level level, BlockPos pos) {
        if (pos.equals(lastToolPos)) {
            return;
        }
        lastToolPos = pos;

        BlockState state = level.getBlockState(pos);
        Inventory inventory = player.getInventory();
        int best = inventory.getSelectedSlot();
        float bestSpeed = toolSpeed(inventory.getItem(best), state);
        for (int i = 0; i < 9; i++) {
            float speed = toolSpeed(inventory.getItem(i), state);
            if (speed > bestSpeed + 0.01F) {
                best = i;
                bestSpeed = speed;
            }
        }
        if (best != inventory.getSelectedSlot()) {
            inventory.setSelectedSlot(best);
        }
    }

    private static float toolSpeed(ItemStack stack, BlockState state) {
        return stack.getItem().getDestroySpeed(stack, state);
    }

    private void releaseKeys(Minecraft mc) {
        setKey(mc.options.keyUp, false);
        setKey(mc.options.keyAttack, false);
    }

    private static void setKey(KeyMapping mapping, boolean down) {
        KeyMapping.set(KeyMappingHelper.getBoundKeyOf(mapping), down);
    }

    // =====================================================================
    //  Вспомогательные проверки
    // =====================================================================

    private static boolean isCentered(LocalPlayer player, double tx, double tz) {
        return Math.abs(player.getX() - tx) < 0.3 && Math.abs(player.getZ() - tz) < 0.3;
    }

    private static boolean needsDig(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && state.getDestroySpeed(level, pos) >= 0.0F;
    }

    private static boolean lavaNear(Level level, BlockPos pos) {
        if (level.getBlockState(pos).is(Blocks.LAVA)) {
            return true;
        }
        for (Direction dir : Direction.values()) {
            if (level.getBlockState(pos.relative(dir)).is(Blocks.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInventoryFull(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void showOverlay(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) {
            mc.gui.setOverlayMessage(Component.literal(text), false);
        }
    }
}
