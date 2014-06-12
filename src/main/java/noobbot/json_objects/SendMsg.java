package noobbot.json_objects;

import com.google.gson.Gson;

/**
 * Created by Valentin on 4/15/2014.
 */
public abstract class SendMsg {

    public String toJson() {
        return new Gson().toJson(new OutMsgWrapper(this));
    }

    protected Object msgData() {
        return this;
    }

    protected abstract String msgType();
}
