import com.eta.lab.screentime.Cfg;

import java.util.ArrayList;
import java.util.List;

/** 纯函数自测：不依赖 Android，桌面 javac 直接跑。 */
public class CoreTest {

    private static int failed = 0;

    private static void eq(String name, long expect, long actual) {
        if (expect != actual) {
            failed++;
            System.out.println("FAIL " + name + " expect=" + expect + " actual=" + actual);
        }
    }

    private static List<int[]> w(int... pairs) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) out.add(new int[]{pairs[i], pairs[i + 1]});
        return out;
    }

    public static void main(String[] args) {
        // parse / join
        List<int[]> p = Cfg.parse("30-420,780-840");
        eq("parse size", 2, p.size());
        eq("parse a0", 30, p.get(0)[0]);
        eq("parse b1", 840, p.get(1)[1]);
        eq("join", "30-420,780-840".length(), Cfg.join(p).length());
        eq("parse empty", 0, Cfg.parse("").size());
        eq("parse junk", 0, Cfg.parse("abc,,-").size());

        // overlap: 普通窗口
        eq("cover hour0 half", 30, Cfg.coveredMinutes(w(30, 420), 0, 60));
        eq("cover hour1 full", 60, Cfg.coveredMinutes(w(30, 420), 60, 120));
        eq("cover hour7 none", 0, Cfg.coveredMinutes(w(30, 420), 420, 480));

        // 跨零点：23:00-07:00
        eq("wrap hour23", 60, Cfg.coveredMinutes(w(1380, 420), 1380, 1440));
        eq("wrap hour3", 60, Cfg.coveredMinutes(w(1380, 420), 180, 240));
        eq("wrap hour7", 0, Cfg.coveredMinutes(w(1380, 420), 420, 480));
        eq("wrap hour12", 0, Cfg.coveredMinutes(w(1380, 420), 720, 780));

        // 多段（并集，最多 60）
        eq("multi overlap cap", 60, Cfg.coveredMinutes(w(0, 40, 20, 60), 0, 60));

        // 窗口时长
        eq("win len normal", 390, Cfg.windowMinutes(new int[]{30, 420}));
        eq("win len wrap", 480, Cfg.windowMinutes(new int[]{1380, 420}));

        // 边界：起止相同 = 不隐藏
        eq("zero window", 0, Cfg.coveredMinutes(w(600, 600), 600, 660));

        if (failed == 0) System.out.println("OK: all core tests passed");
        else System.out.println("FAILED: " + failed);
        if (failed != 0) System.exit(1);
    }
}
