package noobbot.json_objects;

/**
 * Created by Valentin on 4/15/2014.
 */
public final class CarId {
    public String name;
    public String color;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CarId carId = (CarId) o;
        return !(color != null ? !color.equals(carId.color) : carId.color != null) && name.equals(carId.name);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + (color != null ? color.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "CarId{" +
                "name='" + name + '\'' +
                ", color='" + color + '\'' +
                '}';
    }
}
