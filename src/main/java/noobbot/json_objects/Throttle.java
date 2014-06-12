package noobbot.json_objects;

/**
 * Created by Valentin on 4/15/2014.
 */
public final class Throttle extends SendMsg {
    public double value;

    public Throttle(double value) {
        this.value = value;
    }

    @Override
    protected Object msgData() {
        return value;
    }

    @Override
    protected String msgType() {
        return "throttle";
    }
}
