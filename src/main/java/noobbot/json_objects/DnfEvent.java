package noobbot.json_objects;

public class DnfEvent {
    public CarId car;
    public String reason;

    @Override
    public String toString() {
        return "DnfEvent{" +
                "car=" + car +
                ", reason='" + reason + '\'' +
                '}';
    }
}
