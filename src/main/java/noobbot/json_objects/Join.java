package noobbot.json_objects;

/**
 * Created by Valentin on 4/15/2014.
 */
public final class Join extends SendMsg {
    public final String name;
    public final String key;

    public Join(final String name, final String key) {
        this.name = name;
        this.key = key;
    }

    @Override
    protected String msgType() {
        return "join";
    }
}
