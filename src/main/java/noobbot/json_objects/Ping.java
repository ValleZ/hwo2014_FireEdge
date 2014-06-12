package noobbot.json_objects;

/**
 * Created by Valentin on 4/15/2014.
 */
public final class Ping extends SendMsg {
    @Override
    protected String msgType() {
        return "ping";
    }
}
