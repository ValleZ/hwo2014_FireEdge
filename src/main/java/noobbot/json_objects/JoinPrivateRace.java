package noobbot.json_objects;

/**
 * Created by Valentin on 4/24/2014.
 */
public class JoinPrivateRace extends SendMsg {

    private final CreateRace.BotId botId;
    private final String trackName;
    private final int carCount;
    private final String password;

    public JoinPrivateRace(String botName, String botKey, String trackName, int carCount, String password) {
        botId = new CreateRace.BotId();
        botId.name = botName;
        botId.key = botKey;
        this.trackName = trackName;
        this.carCount = carCount;
        this.password = password;
    }

    @Override
    protected String msgType() {
        return "joinRace";
    }

    @Override
    protected Object msgData() {
        return this;
    }
}
