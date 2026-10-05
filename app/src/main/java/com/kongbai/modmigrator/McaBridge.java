package com.kongbai.modmigrator;

import net.querz.mcaselector.io.mca.RegionChunk;
import net.querz.mcaselector.io.mca.RegionMCAFile;
import net.querz.mcaselector.version.ChunkRenderer;
import net.querz.mcaselector.version.ColorMapping;
import net.querz.mcaselector.version.VersionHandler;
import net.querz.nbt.CompoundTag;

import java.io.File;

/**
 * 对 MCA Selector（已移植到 Android 的那一版）的薄封装。
 *
 * # 为什么不再自己解析 .mca
 *
 * 之前是自己写的：只认 GZIP(1) 和 zlib(2) 两种压缩。
 * 而 Minecraft 近几个版本的区域文件还会用到：
 *
 *  - 3 = 不压缩（原样存 NBT）
 *  - 4 = LZ4 块压缩（**lz4-java**，纯 Java，无 JNI）
 *
 * 遇到 3 / 4 时旧代码整块读不出来，表现就是"这个区块里没有方块数据"。
 * MCA Selector 是这块的参考实现，各版本格式都覆盖到了，直接用它更稳。
 *
 * # 一个关键坑：AAR 不带依赖
 *
 * 那个库用的是 `implementation`（不是 `api`），发布出来的 AAR **不包含**
 * 它自己的依赖。少了 `org.atteo.classindex` 时，`VersionHandler.init()`
 * 会在 `ClassIndex.getAnnotated()` 上抛 NoClassDefFoundError ——
 * 版本实现类一个都注册不上，于是所有区块都按"未知版本"处理，
 * 既不报错也画不出东西。所以下面这几个依赖必须在使用方显式声明。
 *
 * 写在 app/build.gradle 里：gson、lz4-java、classindex。
 */
public final class McaBridge {

    private McaBridge() {}

    private static volatile boolean inited = false;

    /** 注册各版本实现。失败要抛出去——静默吞掉的话后面全是"读不出数据"。 */
    public static synchronized void init() {
        if (inited) return;
        VersionHandler.init();
        inited = true;
    }

    public static RegionMCAFile open(File f) throws Exception {
        init();
        RegionMCAFile m = new RegionMCAFile(f);
        m.load(false);
        return m;
    }

    public static int[] presentSlots(RegionMCAFile m) {
        int[] tmp = new int[1024];
        int n = 0;
        for (int i = 0; i < 1024; i++) {
            RegionChunk c = m.getChunk(i);
            if (c == null || c.isEmpty()) continue;
            tmp[n++] = i;
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static int dataVersion(RegionChunk c) {
        CompoundTag d = c.getData();
        if (d == null) return 0;
        try {
            return d.getInt("DataVersion");
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 世界最高 Y。1.18（DataVersion 2838 = 21w37a）起 y 从 -64 到 319，
     * 渲染器要的就是这个上界；之前是 0..255。
     */
    public static int worldHeight(RegionChunk c) {
        return dataVersion(c) >= 2838 ? 320 : 256;
    }

    public static int dataVersionOf(RegionChunk c) {
        return dataVersion(c);
    }

    /** 单个区块的俯视图。scale=1 时是 16×16。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean drawChunk(RegionChunk c, int scale, int[] pixels) {
        if (c == null || c.isEmpty()) return false;
        try {
            int dv = dataVersion(c);
            ChunkRenderer r = VersionHandler.getImpl(dv, ChunkRenderer.class);
            ColorMapping cm = VersionHandler.getImpl(dv, ColorMapping.class);
            int s = 16 * scale;
            int[] water = new int[s * s];
            short[] th = new short[s * s];
            short[] wh = new short[s * s];
            r.drawChunk(c.getData(), cm, 0, 0, scale, pixels, water, th, wh, true, worldHeight(c));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 整个区域文件的俯视图（32×32 个区块）。
     * scale=1 → 512×512，正好一格一个方块。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static int drawRegion(RegionMCAFile m, int scale, int[] pixels) {
        int drawn = 0;
        int size = 32 * 16 * scale;
        int[] water = new int[size * size];
        short[] th = new short[size * size];
        short[] wh = new short[size * size];
        for (int i = 0; i < 1024; i++) {
            RegionChunk c = m.getChunk(i);
            if (c == null || c.isEmpty()) continue;
            try {
                int dv = dataVersion(c);
                ChunkRenderer r = VersionHandler.getImpl(dv, ChunkRenderer.class);
                ColorMapping cm = VersionHandler.getImpl(dv, ColorMapping.class);
                // 槽位 i = (z & 31) * 32 + (x & 31)
                int cx = i & 31;
                int cz = (i >> 5) & 31;
                r.drawChunk(
                    c.getData(), cm, cx * 16 * scale, cz * 16 * scale, scale,
                    pixels, water, th, wh, true, worldHeight(c)
                );
                drawn++;
            } catch (Throwable t) {
                // 单个区块画不出来不影响整张图，跳过即可
            }
        }
        return drawn;
    }

    /** 只画某一层（sectionY 是**区段号**，不是方块 Y：1.18 起从 -4 开始）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean drawLayer(RegionChunk c, int sectionY, int scale, int[] pixels) {
        if (c == null || c.isEmpty()) return false;
        try {
            int dv = dataVersion(c);
            ChunkRenderer r = VersionHandler.getImpl(dv, ChunkRenderer.class);
            ColorMapping cm = VersionHandler.getImpl(dv, ColorMapping.class);
            r.drawLayer(c.getData(), cm, 0, 0, scale, pixels, worldHeight(c));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 这一层是不是整片空（用于跳过没内容的层）。 */
    public static boolean layerEmpty(int[] pixels) {
        for (int p : pixels) {
            if ((p >>> 24) != 0) return false;
        }
        return true;
    }

    public static void deleteChunk(RegionMCAFile m, int slot) {
        m.deleteChunk(slot);
    }

    public static void save(RegionMCAFile m) throws Exception {
        m.save();
    }
}
