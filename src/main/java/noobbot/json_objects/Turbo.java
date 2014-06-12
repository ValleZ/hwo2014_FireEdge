package noobbot.json_objects;

/**
 * Created by Valentin on 4/21/2014.
 */
public final class Turbo extends SendMsg {

    @Override
    protected Object msgData() {
        return "GO!";
    }

    @Override
    protected String msgType() {
        return "turbo";
    }
}