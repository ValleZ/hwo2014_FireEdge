package noobbot.json_objects;

public class OutMsgWrapper {
    public final String msgType;
    public final Object data;

    OutMsgWrapper(final String msgType, final Object data) {
        this.msgType = msgType;
        this.data = data;
    }

    public OutMsgWrapper(final SendMsg sendMsg) {
        this(sendMsg.msgType(), sendMsg.msgData());
    }
}
