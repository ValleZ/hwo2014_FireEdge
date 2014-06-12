package noobbot.json_objects;

/**
 * Created by Valentin on 4/16/2014.
 */
public class LaneSwitch extends SendMsg {
    private final Options value;

    public static enum Options {
        LEFT, RIGHT
    }

    public LaneSwitch(Options turn) {
        this.value = turn;
    }

    @Override
    protected String msgData() {
        if (value == Options.LEFT) {
            return "Left";
        }
        if (value == Options.RIGHT) {
            return "Right";
        }
        return "";
    }

    @Override
    protected String msgType() {
        return "switchLane";
    }
}

