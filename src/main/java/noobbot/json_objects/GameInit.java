package noobbot.json_objects;

import com.google.gson.annotations.SerializedName;

import java.util.Arrays;

public final class GameInit {
    public Race race;

    public static class Race {
        public Track track;

        public Car[] cars;
        public RaceSession raceSession;

        public static class Track {
            public String id;
            public String name;
            public Piece[] pieces;
            public Lane[] lanes;

            public static class Piece {

                public double length;
                public double radius;
                public double angle;
                @SerializedName("switch")
                public boolean haveSwitch;

                public double getRadius(double laneDistanceFromCenter) {
                    return length <= 0 ? (radius + (angle > 0 ? -laneDistanceFromCenter : laneDistanceFromCenter)) : Double.MAX_VALUE;
                }
            }

            public static class Lane {
                public int index;
                public double distanceFromCenter;
            }
        }

        public static class RaceSession {
            public int laps;
            public long maxLapTimeMs;
            public boolean quickRace;
        }

        public static class Car {
            public CarId id;
            public CarDimensions dimensions;
        }


        @Override
        public String toString() {
            return "Race{" +
                    "track=" + track +
                    ", cars=" + Arrays.toString(cars) +
                    ", raceSession=" + raceSession +
                    '}';
        }
    }
}
