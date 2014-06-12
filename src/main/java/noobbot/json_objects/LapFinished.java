package noobbot.json_objects;

public final class LapFinished {
    public CarId car;
    public RaceTime lapTime;
    public RaceTime raceTime;
    public Ranking ranking;

    public static class RaceTime {
        public double laps;
        public double ticks;
        public double millis;

        @Override
        public String toString() {
            return "RaceTime{" +
                    "laps=" + laps +
                    ", ticks=" + ticks +
                    ", millis=" + millis +
                    '}';
        }
    }
    public static class Ranking {
        public double overall;
        public double fastestLap;

        @Override
        public String toString() {
            return "Ranking{" +
                    "overall=" + overall +
                    ", fastestLap=" + fastestLap +
                    '}';
        }
    }

    @Override
    public String toString() {
        return "LapFinished{" +
                "car=" + car +
                ", lapTime=" + lapTime +
                ", raceTime=" + raceTime +
                ", ranking=" + ranking +
                '}';
    }
}
