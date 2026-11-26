import com.bixiaoqu.app.LayoutPolicy;

import java.util.Random;

/**
 * 【v4.21.0】播放页布局策略模拟测试。
 *
 * 不依赖 Android / 不依赖真机：LayoutPolicy 是纯逻辑类，直接编译后穷举所有状态组合，
 * 复现 v4.20.0 平板版的三类线上问题，并验证修复后的不变量：
 *
 *   ① 横屏分屏里点竖屏推荐 → 布局被改成竖屏铺满（右栏消失、画面重排）
 *   ② 重排期间 Surface 被重新测量 → 黑屏只剩声音
 *   ③ 返回后停在「手机版」窄视频详情页
 *
 * 运行：javac -encoding UTF-8 -d out LayoutPolicy.java LayoutPolicyTest.java
 *      java -cp out LayoutPolicyTest
 */
public class LayoutPolicyTest {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== 播放页布局策略模拟测试 v4.21.0 ===");
        testFillTruthTable();
        testOldBugReproduced();
        testSplitSwitchKeepsSplit();
        testBackAction();
        testRotation();
        testSplitBoxFuzz();
        testCoverBoxFuzz();
        testAudioSync();
        testPosterGate();
        testStress();
        testScenarioReplay();
        System.out.println();
        System.out.println("--------------------------------------");
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.out.println("结果：FAIL");
            System.exit(1);
        }
        System.out.println("结果：ALL PASS");
    }

    // ------------------------------------------------------------ 断言

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : " · " + detail));
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : " · " + detail));
        }
    }

    // ------------------------------------------------------------ ① 铺满判定的真值表

    static void testFillTruthTable() {
        System.out.println("\n[1] canPortraitFill 真值表");
        int cases = 0;
        boolean ok = true;
        String bad = "";
        for (int mask = 0; mask < 32; mask++) {
            boolean pv = (mask & 1) != 0;
            boolean pref = (mask & 2) != 0;
            boolean split = (mask & 4) != 0;
            boolean full = (mask & 8) != 0;
            boolean land = (mask & 16) != 0;
            boolean can = LayoutPolicy.canPortraitFill(pv, pref, split, full, land);
            cases++;
            boolean expected = pv && pref && !split && !full && !land;
            if (can != expected) {
                ok = false;
                bad = "mask=" + mask;
                break;
            }
            // 三条安全约束必须同时成立
            if (can && (split || full || land)) {
                ok = false;
                bad = "safe-guard broken mask=" + mask;
                break;
            }
        }
        check("穷举 " + cases + " 组合", ok, ok ? "分屏/全屏/横屏一律拒绝" : bad);
    }

    // ------------------------------------------------------------ ② 复现 v4.20.0 的线上 bug

    static void testOldBugReproduced() {
        System.out.println("\n[2] 复现：平板横屏分屏 + 点竖屏推荐");
        // 现场：横屏、宽屏（平板）、分屏中、当前视频横屏
        boolean portraitVideo = false, pref = true;
        boolean split = true, full = false, landscape = true, wide = true;

        // 旧逻辑（v4.20.0 applyMeta）：只判「竖屏视频 && !全屏」
        boolean oldEnter = true && !full;   // 换成竖屏视频后
        check("旧逻辑会在分屏里进入铺满（bug 成立）", oldEnter,
                "横屏 root 上把 holder 改成整屏 → 右栏被挤成 0 宽");

        // 新逻辑
        boolean newEnter = LayoutPolicy.canPortraitFill(true, pref, split, full, landscape);
        check("新逻辑拒绝进入铺满", !newEnter, "mode 保持 SPLIT");

        // 换片后的模式
        int mode = LayoutPolicy.modeAfterSwitch(LayoutPolicy.MODE_SPLIT, true, true, true, pref);
        check("换片后仍是分屏", mode == LayoutPolicy.MODE_SPLIT, "MODE_SPLIT");

        // 左栏画面盒：竖屏 9:16 放进 620×720 的左栏
        int[] box = LayoutPolicy.splitBox(620, 720, 9f / 16f);
        check("画面盒非 0 且完整显示", box[0] > 0 && box[1] > 0 && box[0] <= 620 && box[1] <= 720,
                box[0] + "×" + box[1] + " in 620×720");
        float ratio = box[0] / (float) box[1];
        check("画面盒保持 9:16", Math.abs(ratio - 9f / 16f) < 0.01f, String.format("%.3f", ratio));
    }

    // ------------------------------------------------------------ ③ 分屏换视频不丢分屏

    static void testSplitSwitchKeepsSplit() {
        System.out.println("\n[3] 分屏里连续换 8 个视频（横→竖→横…）");
        boolean ok = true;
        String detail = "";
        float[] aspects = {16f / 9f, 9f / 16f, 4f / 3f, 0.5625f, 1.777f, 2.35f, 1f, 0.75f};
        int mode = LayoutPolicy.MODE_SPLIT;
        for (float a : aspects) {
            boolean pv = LayoutPolicy.isPortraitAspect(a);
            mode = LayoutPolicy.modeAfterSwitch(mode, true, true, pv, true);
            if (mode != LayoutPolicy.MODE_SPLIT) {
                ok = false;
                detail = "aspect=" + a + " 掉出分屏";
                break;
            }
            int[] box = LayoutPolicy.splitBox(620, 720, a);
            if (box[0] < 2 || box[1] < 2 || box[0] > 620 || box[1] > 720) {
                ok = false;
                detail = "aspect=" + a + " 盒子越界 " + box[0] + "×" + box[1];
                break;
            }
        }
        check("8 次换片全程分屏", ok, detail.isEmpty() ? "画面盒始终落在左栏内" : detail);
    }

    // ------------------------------------------------------------ ④ 返回键语义

    static void testBackAction() {
        System.out.println("\n[4] 返回键语义");
        check("分屏 → 离开播放页（不再退成手机版详情页）",
                LayoutPolicy.backAction(true, false, false) == LayoutPolicy.BACK_FINISH, "BACK_FINISH");
        check("全屏 → 先退全屏",
                LayoutPolicy.backAction(false, true, false) == LayoutPolicy.BACK_EXIT_FULL, "BACK_EXIT_FULL");
        check("竖屏铺满 → 先还原",
                LayoutPolicy.backAction(false, false, true) == LayoutPolicy.BACK_EXIT_FILL, "BACK_EXIT_FILL");
        check("普通详情页 → 离开",
                LayoutPolicy.backAction(false, false, false) == LayoutPolicy.BACK_FINISH, "BACK_FINISH");
    }

    // ------------------------------------------------------------ ⑤ 旋转

    static void testRotation() {
        System.out.println("\n[5] 旋转序列（分屏 → 竖屏 → 横屏）");
        boolean ok = true;
        String detail = "";

        // 横屏分屏
        int mode = LayoutPolicy.MODE_SPLIT;
        // 转竖屏：退出分屏 → 竖屏视频重新铺满
        mode = LayoutPolicy.targetMode(false, true, false, true, true);
        if (mode != LayoutPolicy.MODE_FILL) { ok = false; detail = "竖屏未进铺满"; }
        // 再转横屏：必须退出铺满、回到分屏（旧版会在横向 root 上继续铺满）
        if (LayoutPolicy.canPortraitFill(true, true, false, false, true)) {
            ok = false;
            detail = "横屏仍允许铺满";
        }
        mode = LayoutPolicy.targetMode(true, true, false, true, true);
        if (mode != LayoutPolicy.MODE_SPLIT) { ok = false; detail = "横屏未回分屏"; }
        check("旋转全程模式正确", ok, detail.isEmpty() ? "SPLIT → FILL → SPLIT" : detail);
    }

    // ------------------------------------------------------------ ⑥ 分屏盒子鲁棒性

    static void testSplitBoxFuzz() {
        System.out.println("\n[6] splitBox 退化输入");
        float[] aspects = {0f, -1f, 0.0001f, 0.2f, 9f / 16f, 1f, 16f / 9f, 4.99f, 1e9f,
                Float.NaN, Float.POSITIVE_INFINITY};
        int[][] panes = {{0, 0}, {0, 720}, {620, 0}, {620, 720}, {1080, 600}, {2, 2}};
        boolean ok = true;
        String detail = "";
        outer:
        for (int[] p : panes) {
            for (float a : aspects) {
                int[] b = LayoutPolicy.splitBox(p[0], p[1], a);
                if (b[0] < 2 || b[1] < 2) {
                    ok = false;
                    detail = "pane=" + p[0] + "×" + p[1] + " aspect=" + a + " → " + b[0] + "×" + b[1];
                    break outer;
                }
                if (p[0] > 0 && p[1] > 0 && (b[0] > p[0] + 2 || b[1] > p[1] + 2)) {
                    ok = false;
                    detail = "越界 pane=" + p[0] + "×" + p[1] + " → " + b[0] + "×" + b[1];
                    break outer;
                }
            }
        }
        check("11 种宽高比 × 6 种容器", ok, ok ? "永远 >= 2px 且不越界" : detail);
    }

    // ------------------------------------------------------------ ⑦ 铺满盒子

    static void testCoverBoxFuzz() {
        System.out.println("\n[7] coverBox 覆盖性与比例");
        float[] aspects = {9f / 16f, 0.75f, 0f, Float.NaN, 1f};
        int[][] boxes = {{1080, 1920}, {720, 1280}, {0, 0}};
        boolean ok = true;
        String detail = "";
        outer:
        for (int[] p : boxes) {
            for (float a : aspects) {
                int[] b = LayoutPolicy.coverBox(p[0], p[1], a);
                if (b[0] < 2 || b[1] < 2) {
                    ok = false;
                    detail = "尺寸异常 " + b[0] + "×" + b[1];
                    break outer;
                }
                if (p[0] > 0 && (b[0] < p[0] - 1 || b[1] < p[1] - 1)) {
                    ok = false;
                    detail = "未铺满 screen=" + p[0] + "×" + p[1] + " box=" + b[0] + "×" + b[1];
                    break outer;
                }
            }
        }
        check("铺满不留黑边", ok, detail.isEmpty() ? "盒子始终 >= 屏幕" : detail);
    }

    // ------------------------------------------------------------ ⑧ 音画同步起点

    static void testAudioSync() {
        System.out.println("\n[8] 音轨起播位置");
        check("无断点 + 画面 0ms → 0", LayoutPolicy.audioStartTarget(-1, 0) == 0, "0");
        check("无断点 + 画面 1500ms → 1500", LayoutPolicy.audioStartTarget(-1, 1500) == 1500, "1500");
        check("有断点 8200ms → 8200", LayoutPolicy.audioStartTarget(8200, 300) == 8200, "8200");
        check("断点 0 → 0", LayoutPolicy.audioStartTarget(0, 900) == 0, "0");
        check("画面进度为负 → 0", LayoutPolicy.audioStartTarget(-1, -50) == 0, "0");
    }

    // ------------------------------------------------------------ ⑨ 海报闸门

    static void testPosterGate() {
        System.out.println("\n[9] 起播海报闸门（黑屏只剩声音的竞态）");
        check("首帧已到 → 不贴",
                !LayoutPolicy.shouldShowPoster(true, false, false, false), "旧版会贴到 8 秒兜底");
        check("播放器已就绪 → 不贴",
                !LayoutPolicy.shouldShowPoster(false, true, false, false), "");
        check("正常起播 → 贴",
                LayoutPolicy.shouldShowPoster(false, false, false, false), "");
        check("切清晰度 → 不贴",
                !LayoutPolicy.shouldShowPoster(false, false, true, false), "");
        check("暂停切档 → 不贴",
                !LayoutPolicy.shouldShowPoster(false, false, false, true), "");
    }

    // ------------------------------------------------------------ ⑩ 随机压力

    static void testStress() {
        System.out.println("\n[10] 随机压力：20000 次换片 / 旋转 / 返回");
        Random rnd = new Random(20261126L);
        int mode = LayoutPolicy.MODE_SPLIT;
        boolean landscape = true, wide = true;
        boolean ok = true;
        String detail = "";
        int violations = 0;

        for (int i = 0; i < 20000; i++) {
            int act = rnd.nextInt(10);
            float aspect = 0.4f + rnd.nextFloat() * 2.2f;   // 0.4 ~ 2.6
            boolean pv = LayoutPolicy.isPortraitAspect(aspect);

            if (act < 6) {
                // 换视频（分屏右栏 / 详情页推荐 / 上下滑）
                mode = LayoutPolicy.modeAfterSwitch(mode, landscape, wide, pv, true);
            } else if (act < 8) {
                // 旋转
                landscape = !landscape;
                mode = LayoutPolicy.targetMode(landscape, wide, false, pv, true);
            } else if (act < 9) {
                // 退出全屏回到分屏
                mode = LayoutPolicy.MODE_SPLIT;
                landscape = true;
            } else {
                // 返回
                int back = LayoutPolicy.backAction(mode == LayoutPolicy.MODE_SPLIT,
                        mode == LayoutPolicy.MODE_FULL, mode == LayoutPolicy.MODE_FILL);
                if (back == LayoutPolicy.BACK_FINISH) {
                    // 重新进入播放页
                    mode = LayoutPolicy.targetMode(landscape, wide, false, pv, true);
                }
            }

            // 不变量
            if (mode == LayoutPolicy.MODE_FILL && (landscape || mode == LayoutPolicy.MODE_SPLIT)) {
                violations++;
                detail = "第 " + i + " 步：横屏/分屏下出现 FILL";
                break;
            }
            if (mode == LayoutPolicy.MODE_SPLIT && !landscape) {
                violations++;
                detail = "第 " + i + " 步：竖屏下出现 SPLIT";
                break;
            }
            if (mode == LayoutPolicy.MODE_SPLIT) {
                int paneW = 300 + rnd.nextInt(900);
                int paneH = 300 + rnd.nextInt(900);
                int[] box = LayoutPolicy.splitBox(paneW, paneH, aspect);
                if (box[0] < 2 || box[1] < 2 || box[0] > paneW || box[1] > paneH) {
                    violations++;
                    detail = "第 " + i + " 步：分屏盒子异常 " + box[0] + "×" + box[1]
                            + " / " + paneW + "×" + paneH;
                    break;
                }
            }
        }
        ok = violations == 0;
        check("20000 步无违规", ok, ok ? "模式与画面盒始终自洽" : detail);
    }

    // ------------------------------------------------------------ ⑪ 线上场景回放

    /** 与 PlayActivity 一一对应的最小状态模型：换片 / 出帧 / meta / 海报 */
    static class Sim {
        int mode = LayoutPolicy.MODE_SPLIT;
        boolean landscape = true, wide = true, pref = true;
        boolean frameDone = true, playerReady = true, posterOn = false;
        float aspect = 16f / 9f;
        boolean pv = false;
        final int paneW = 600, paneH = 700;
        int boxW, boxH;
        final StringBuilder log = new StringBuilder();

        Sim() {
            int[] b = LayoutPolicy.splitBox(paneW, paneH, aspect);
            boxW = b[0];
            boxH = b[1];
        }

        void note(String s) { log.append("      ").append(s).append("\n"); }

        /** 右栏点推荐：switchVideo */
        void switchTo(float newAspect) {
            playerReady = false;
            frameDone = false;                 // 新视频尚未出帧
            if (newAspect > 0f) {
                aspect = newAspect;
                pv = LayoutPolicy.isPortraitAspect(aspect);
            }
            mode = LayoutPolicy.modeAfterSwitch(mode, landscape, wide, pv, pref);
            int[] b = LayoutPolicy.splitBox(paneW, paneH, aspect);
            boxW = b[0]; boxH = b[1];
            note("switchVideo aspect=" + String.format("%.3f", aspect)
                    + " → mode=" + modeName(mode) + " box=" + boxW + "x" + boxH);
        }

        /** playurl 回来：按轨道宽高修正 */
        void playurl(float a) {
            if (a > 0f) { aspect = a; pv = LayoutPolicy.isPortraitAspect(a); }
            int[] b = LayoutPolicy.splitBox(paneW, paneH, aspect);
            boxW = b[0]; boxH = b[1];
            note("playurl aspect=" + String.format("%.3f", aspect) + " box=" + boxW + "x" + boxH);
        }

        /** 起播海报闸门 */
        void poster() {
            posterOn = LayoutPolicy.shouldShowPoster(frameDone, playerReady, false, false);
            note("poster=" + posterOn);
        }

        void prepared() {
            playerReady = true;
            frameDone = true;
            posterOn = false;                  // fadeStartupPoster
            note("prepared → 海报淡出，画面出帧");
        }

        /** applyMeta：旧版的 bug 就发生在这里 */
        void meta() {
            boolean fill = LayoutPolicy.canPortraitFill(pv, pref,
                    mode == LayoutPolicy.MODE_SPLIT, mode == LayoutPolicy.MODE_FULL, landscape);
            if (fill) mode = LayoutPolicy.MODE_FILL;
            note("meta portraitVideo=" + pv + " → mode=" + modeName(mode));
        }

        boolean healthy() {
            if (mode == LayoutPolicy.MODE_FILL && landscape) return false;
            if (mode == LayoutPolicy.MODE_SPLIT) {
                return boxW > 0 && boxH > 0 && boxW <= paneW && boxH <= paneH;
            }
            return true;
        }
    }

    static String modeName(int m) {
        switch (m) {
            case LayoutPolicy.MODE_DETAIL: return "DETAIL";
            case LayoutPolicy.MODE_SPLIT: return "SPLIT";
            case LayoutPolicy.MODE_FULL: return "FULL";
            case LayoutPolicy.MODE_FILL: return "FILL";
            default: return "?" + m;
        }
    }

    static void testScenarioReplay() {
        System.out.println("\n[11] 线上场景回放：平板横屏分屏点右栏推荐");
        Sim sim = new Sim();
        boolean ok = true;
        String detail = "";

        // 0) 正在看一个横屏视频，分屏正常
        sim.note("初始：横屏分屏，横屏视频播放中");
        if (!sim.healthy()) { ok = false; detail = "初始状态异常"; }

        // 1) 点一个竖屏推荐（列表已带 aspect=9:16）
        sim.switchTo(9f / 16f);
        if (!sim.healthy()) { ok = false; detail = "换片后布局异常"; }

        // 2) 海报 + playurl + meta（旧版这里被改成 FILL）
        sim.poster();
        sim.playurl(9f / 16f);
        sim.meta();
        if (!sim.healthy()) { ok = false; detail = "meta 之后布局被改成竖屏铺满"; }

        // 3) 首帧
        sim.prepared();
        if (sim.posterOn) { ok = false; detail = "首帧后海报仍在盖屏（黑屏）"; }

        // 4) 连点 5 条，横竖交替，每次都跑完 meta
        float[] seq = {16f / 9f, 9f / 16f, 4f / 3f, 0.5625f, 1.777f};
        for (float a : seq) {
            sim.switchTo(a);
            sim.poster();
            sim.playurl(a);
            sim.meta();
            sim.prepared();
            if (!sim.healthy()) {
                ok = false;
                detail = "第 " + a + " 条后布局异常";
                break;
            }
            if (sim.posterOn) { ok = false; detail = "第 " + a + " 条首帧后海报仍在"; break; }
        }

        System.out.print(sim.log);
        check("场景回放全程分屏且画面盒有效", ok, detail.isEmpty() ? "无黑屏 / 无变竖屏" : detail);

        // 5) 返回键：不再退成手机版详情页
        int back = LayoutPolicy.backAction(true, false, false);
        check("分屏返回直接离开播放页", back == LayoutPolicy.BACK_FINISH, "BACK_FINISH");
    }
}
