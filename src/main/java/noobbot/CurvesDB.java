package noobbot;

import android.util.LongSparseArray;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public final class CurvesDB {
    public static void load(LongSparseArray<SwitchRadiusInfo[]> weirdBendedSwitchesInfo) {
        if (weirdBendedSwitchesInfo.size() == 0) {
            try {
                InputStream is = CurvesDB.class.getResourceAsStream("/curves.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"));
                CurvesJson.Curves[] curves = new Gson().fromJson(reader, CurvesJson.class).curves;
                int pointsCount = 0;
                for (CurvesJson.Curves curve : curves) {
                    SwitchRadiusInfo[] values = new SwitchRadiusInfo[curve.values.length / 2];
                    for (int i = 0; i < curve.values.length; i += 2) {
                        values[i / 2] = new SwitchRadiusInfo(curve.values[i], curve.values[i + 1]);
                    }
                    pointsCount += curve.values.length / 2;
                    weirdBendedSwitchesInfo.put(curve.key, values);
                }
                System.out.println("Loaded " + curves.length + " curves, " + pointsCount + " points.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static class CurvesJson {
        public Curves[] curves;

        public static class Curves {
            public long key;
            public double[] values;
        }
    }
}
