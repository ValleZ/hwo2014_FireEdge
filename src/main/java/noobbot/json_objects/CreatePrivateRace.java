package noobbot.json_objects;

/**
 * Created by Valentin on 4/24/2014.
 */
public class CreatePrivateRace extends SendMsg {

    private final CreateRace.BotId botId;
    private final String trackName;
    private final int carCount;
    private final String password;

    public CreatePrivateRace(String botName, String botKey, String trackName, int carCount, String password) {
        botId = new CreateRace.BotId();
        botId.name = botName;
        botId.key = botKey;
        this.trackName = trackName;
        this.carCount = carCount;
        this.password = password;
    }

    @Override
    protected String msgType() {
        return "createRace";
    }

    @Override
    protected Object msgData() {
        return this;
    }
}
