package noobbot.json_objects;

/**
 * Created by Valentin on 4/21/2014.
 */
public final class TurboData {
    public double turboDurationMilliseconds = 500.0f;
    public double turboDurationTicks = 30.0f;
    public double turboFactor = 3.0f;

    @Override
    public String toString() {
        return "TurboData{" +
                "turboDurationMilliseconds=" + turboDurationMilliseconds +
                ", turboDurationTicks=" + turboDurationTicks +
                ", turboFactor=" + turboFactor +
                '}';
    }
}
