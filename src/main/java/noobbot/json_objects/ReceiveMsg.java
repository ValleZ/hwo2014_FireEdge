package noobbot.json_objects;

import com.google.gson.Gson;

public abstract class ReceiveMsg {

    public String toJson() {
        return new Gson().toJson(new MsgWrapper(this));
    }

    protected Object msgData() {
        return this;
    }

    protected abstract String msgType();
}
