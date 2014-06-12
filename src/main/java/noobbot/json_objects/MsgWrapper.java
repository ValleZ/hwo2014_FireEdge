package noobbot.json_objects;

/**
 * Created by Valentin on 4/15/2014.
 */
public class MsgWrapper {
    public final String msgType;
    public final Object data;
    public int gameTick;


    MsgWrapper(final String msgType, final Object data) {
        this.msgType = msgType;
        this.data = data;
    }

    public MsgWrapper(final ReceiveMsg msg) {
        this(msg.msgType(), msg.msgData());
    }
}
