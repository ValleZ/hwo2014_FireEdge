package noobbot.json_objects;

/**
 * Created by Valentin on 4/17/2014.
 */
public class CreateRace extends SendMsg {

    private final BotId botId;
    private final String trackName;
    private final int carCount;

    public CreateRace(String botName, String botKey, String trackName, int carCount) {
        botId = new BotId();
        botId.name = botName;
        botId.key = botKey;
        this.trackName = trackName;
        this.carCount = carCount;
    }

    @Override
    protected String msgType() {
        return "createRace";
    }

    @Override
    protected Object msgData() {
        return this;
    }

    public static class BotId {
        public String name;
        public String key;
    }
}
