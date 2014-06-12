package noobbot;

import android.util.LongSparseArray;
import noobbot.json_objects.CarId;
import noobbot.json_objects.CarPosition;
import noobbot.json_objects.GameInit;
import noobbot.json_objects.LaneSwitch;

import java.util.*;

public final class Game {
    public static final int ANGLE_STATE_NOT_INITED = 0;
    public static final int ANGLE_STATE_PERFECT_PARAMS_FOUND = 1;
    public static final int ANGLE_STATE_NEED_ALL_PARAMS = 2;

    public static final double MAX_ANGLE = 60;

    public static final double MAX_RACCEL = 2;
    public static final double MAX_SPEED = 20;
    private static final double MIN_SPEED = 3;
    private static final int DIST_TO_ANALYZE = 1000;
    public static final double DECREASE_RACC_K = 1;
    public static final double EXTRA_SAFETY_ANGLE_FOR_LANE_SWITCHING = 0.5;
    public static final double EXTRA_SAFETY_ANGLE_FOR_TURBO = 0;

    public static final double SWITCH_PENALTY_FOR_BENDED_PIECE = 40;

    public double drag = 0.1;
    public double mass = 4.94983;
    private double powerDeficit;
    private int lapsCount;
    public int desiredSpeedDecreasesCount, desiredSpeedIncreasesCount;
    private String myCarColor;
    private ArrayList<CarInfo> allCars = new ArrayList<>();
    public GameInit.Race.Track track;
    private double turboFactor = 1;
    private boolean distanceDebugActive;
    int distanceDebugActiveLap = -1;
    private final double[] desiredSpeeds = new double[DIST_TO_ANALYZE];
    private final ArrayList<RadiusChange> radiuses = new ArrayList<>();
    private final ArrayList<RadiusChange> recycledRadiusChange = new ArrayList<>();
    private final ArrayList<CarInfo> recycledCarInfos = new ArrayList<>();
    private ArrayList<CarInfo> allCars2 = new ArrayList<>();

    private final LongSparseArray<SwitchRadiusInfo[]> weirdBendedSwitchesInfo = new LongSparseArray<>();
    private final AngleState emulatedAngleState = new AngleState(this);
    private final Random random = new Random();
    private FormulaFinder formulaFinder;

    private boolean debug = false;
    private int turboTicksLeft, turboCautionTicksLeft;
    private GameInit.Race.Car[] carsGeometricInfos;
    private GameInit.Race.Car myCarGeometricInfo;
    private double predictedTicksToRideTheDistance;
    public String nearestCarName, nearestCarRearName, nearestCarFrontName;
    public int nearestCarEndLineIndex = 0;
    public double nearestCarRearDist, nearestCarRearDistDeltaVelocity, nearestCarRearTimeToCollision;
    public double nearestCarFrontDist, nearestCarFrontDistDeltaVelocity, nearestCarFrontTimeToCollision;

    public int angleState = ANGLE_STATE_NOT_INITED;
    public volatile int anglePredictorPointsCount = 0;
    public volatile double anglePredictorCurrentError = 1;
    public volatile double anglePredictorMinRAccell = 0.3;
    public volatile double anglePredictorA = 0.53059;
    public volatile double anglePredictorB = 0.1;
    public volatile double anglePredictorC = 0.00125;

    public static final double MIN_SAFETY_ANGLE = 0.78;
    public double safetyAngle = MIN_SAFETY_ANGLE;
    public MedianFinder[] powerDeficitsByPiece;
    private IntDouble[] powerDeficitsByPieceBuf;
    private int lastTurboDeficitPieceIndexUpdate = -1;
    private int lastTurboDeficitLapUpdate = -1;
    private final HashSet<String> dnfColors = new HashSet<>(8);
    private final HashSet<String> crashedCars = new HashSet<>(8);

    public Game() {
        CurvesDB.load(weirdBendedSwitchesInfo);
    }

//    public Game(int anxiety) {
//        CurvesDB.load(weirdBendedSwitchesInfo);
//        ANXIETY = anxiety;
//    }

    public double getLaneLength(GameInit.Race.Track.Piece piece, double laneDistanceFromCenterStart, double laneDistanceFromCenterEnd) {
        boolean haveSwitch = Math.abs(laneDistanceFromCenterStart - laneDistanceFromCenterEnd) > 0.00001;
        if (piece.length <= 0.0000001) {
            double radiusStart = piece.getRadius(laneDistanceFromCenterStart);
            double angle = Math.abs(piece.angle);
            double lenStart = angle * radiusStart * Math.PI / 360;
            if (haveSwitch) {
                double radiusEnd = piece.getRadius(laneDistanceFromCenterEnd);
                double lenEnd = angle * radiusEnd * Math.PI / 360;
                double bumpSize = roadBumps.get(getBumpKey(Math.abs(piece.angle), radiusStart, radiusEnd), NO_BUMP).size;
                return lenStart + lenEnd + 2.5865625 - bumpSize;
            } else {
                return lenStart * 2;
            }
        } else {
            if (haveSwitch) {
                return piece.length - ((piece.length - 70) * (2.9045727775144705 - 2.060274992933774) / 30 - 2.9045727775144705) - roadBumps.get(getBumpKey(piece.length), NO_BUMP).size;
            } else {
                return piece.length;
            }
        }
    }

    private static final RoadBump NO_BUMP = new RoadBump(0);

    public void printRoadBumps() {
        for (int i = 0; i < roadBumps.size(); i++) {
            RoadBump bump = roadBumps.get(roadBumps.keyAt(i));
            if (bump.description != null) {
                System.out.println(bump.description + ";");
            }
        }
        System.out.println("{\"curves\":[");
        for (int i = 0; i < weirdBendedSwitchesInfo.size(); i++) {
            long key = weirdBendedSwitchesInfo.keyAt(i);
            SwitchRadiusInfo[] rs = weirdBendedSwitchesInfo.get(key);
            System.out.print("{\"key\":\"" + key + "\", \"values\":" + Arrays.toString(rs) + "}");
            if (i < weirdBendedSwitchesInfo.size() - 1) {
                System.out.println(",");
            }
        }
        System.out.println("]}");
    }

    public void learnRoadBumpForCurve(double size, double angle, double radiusStart, double radiusEnd) {
        long key = getBumpKey(angle, radiusStart, radiusEnd);
        learnRoadBump(key, new RoadBump(size, "putCurve(getBumpKey(" + angle + ", " + radiusStart + ", " + radiusEnd + "), new RoadBump(" + size + "))"));
    }

    public void learnRoadBumpForStraight(double size, double length) {
        long key = getBumpKey(length);
        learnRoadBump(key, new RoadBump(size, "putCurve(getBumpKey(" + length + "), new RoadBump(" + size + "))"));
    }

    private void learnRoadBump(long key, RoadBump roadBump) {
        RoadBump existingBump = roadBumps.get(key);
        if (existingBump == null) {
            roadBumps.put(key, roadBump);
            System.out.println("ROADBUMP LEARNED! " + roadBump.size + " " + roadBump.description);
        } else if (Math.abs(existingBump.size - roadBump.size) > 0.000001) {
            System.out.println("ROADBUMP COLLIZION! adding " + roadBump.size + " existing " + existingBump.size);
        } else {
            System.out.println("ROADBUMP ALREADY EXIST! " + roadBump.size + " existing " + existingBump.size + " key " + key);
        }
    }

    public void learnWeirdBendedSwitchCurve(double realR, double angle, double radiusStart, double radiusEnd, double pieceOffsRelative) {
        long key = getBumpKey(angle, radiusStart, radiusEnd);
        SwitchRadiusInfo[] pointsForThisSwitch = weirdBendedSwitchesInfo.get(key);
        if (pointsForThisSwitch == null) {
            pointsForThisSwitch = new SwitchRadiusInfo[1];
        } else {
            SwitchRadiusInfo[] newPointsForThisSwitch = new SwitchRadiusInfo[pointsForThisSwitch.length + 1];
            System.arraycopy(pointsForThisSwitch, 0, newPointsForThisSwitch, 0, pointsForThisSwitch.length);
            pointsForThisSwitch = newPointsForThisSwitch;
        }
        pointsForThisSwitch[pointsForThisSwitch.length - 1] = new SwitchRadiusInfo(realR, pieceOffsRelative);
        Arrays.sort(pointsForThisSwitch);
        weirdBendedSwitchesInfo.put(key, pointsForThisSwitch);
    }

    private static long getBumpKey(double angle, double radiusStart, double radiusEnd) {
        return 360 + Math.round(angle * 10) + Math.round(radiusStart) * 10000 + Math.round(radiusEnd) * 10000000;
    }

    private static long getBumpKey(double length) {
        return -Math.round(length);
    }

    private static final LongSparseArray<RoadBump> roadBumps = new LongSparseArray<RoadBump>() {{
        put(getBumpKey(110.0), new RoadBump(-0.0991906644959144));
        put(getBumpKey(102.0), new RoadBump(-0.01705770706394283));
        put(getBumpKey(99.0), new RoadBump(0.007952105619793315));
        put(getBumpKey(94.0), new RoadBump(0.04147746603470637));
        put(getBumpKey(90.0), new RoadBump(0.06002631535529801));
        put(getBumpKey(78.0), new RoadBump(0.06041313781424584));
        put(getBumpKey(20.0), new RoadBump(5.023328484310708));
        put(getBumpKey(22.5, 180.0, 200.0), new RoadBump(-0.05579534365945982));
        put(getBumpKey(22.5, 190.0, 210.0), new RoadBump(0.07172663336987739));
        put(getBumpKey(22.5, 200.0, 180.0), new RoadBump(-0.055049870425948555));
        put(getBumpKey(22.5, 200.0, 220.0), new RoadBump(0.18758626375668364));
        put(getBumpKey(22.5, 210.0, 190.0), new RoadBump(0.07247468043963678));
        put(getBumpKey(22.5, 220.0, 200.0), new RoadBump(0.18833654640060793));
        put(getBumpKey(45.0, 60.0, 80.0), new RoadBump(-0.9386328144017853));
        put(getBumpKey(45.0, 80.0, 60.0), new RoadBump(-0.937259264516042));
        put(getBumpKey(45.0, 80.0, 100.0), new RoadBump(-0.17586525055001356));
        put(getBumpKey(45.0, 90.0, 110.0), new RoadBump(0.09689469773670645));
        put(getBumpKey(45.0, 100.0, 80.0), new RoadBump(-0.1744525758566695));
        put(getBumpKey(45.0, 100.0, 120.0), new RoadBump(0.3221868200843083));
        put(getBumpKey(45.0, 110.0, 90.0), new RoadBump(0.09831932302624846));
        put(getBumpKey(45.0, 120.0, 100.0), new RoadBump(0.3236204612841238));
        put(getBumpKey(60.0, 50.0, 70.0), new RoadBump(-0.4638648539506889));
        put(getBumpKey(60.0, 70.0, 50.0), new RoadBump(-0.4620905339157071));
        put(getBumpKey(60.0, 70.0, 90.0), new RoadBump(0.31941110348151724));
        put(getBumpKey(60.0, 90.0, 70.0), new RoadBump(0.3212336206234747));
        put(getBumpKey(80.0, 30.0, 50.0), new RoadBump(-0.7028848555601934));
        put(getBumpKey(80.0, 50.0, 30.0), new RoadBump(-0.7007291910089055));
        put(getBumpKey(80.0, 50.0, 70.0), new RoadBump(0.5273982133530248));
        put(getBumpKey(80.0, 70.0, 50.0), new RoadBump(0.5296590095087126));
        put(getBumpKey(90.0, 35.0, 55.0), new RoadBump(0.2103215463410697));
        put(getBumpKey(90.0, 55.0, 35.0), new RoadBump(0.21272728799460872));
        put(getBumpKey(90.0, 55.0, 75.0), new RoadBump(1.2108935727274321));
        put(getBumpKey(90.0, 75.0, 55.0), new RoadBump(1.213372955837519));
    }};

    public void setMyCarColor(String carId) {
        myCarColor = carId;
        setupMyCarGeometricInfo();
    }

    public boolean isMyCar(CarId car) {
        return myCarColor != null && myCarColor.equals(car.color);
    }

    private void setupMyCarGeometricInfo() {
        if (carsGeometricInfos != null && myCarColor != null) {
            for (GameInit.Race.Car carGeometricInfo : carsGeometricInfos) {
                if (carGeometricInfo != null && carGeometricInfo.id != null && carGeometricInfo.id.color.equals(myCarColor)) {
                    myCarGeometricInfo = carGeometricInfo;
                }
            }
        }
    }

    public void init(GameInit gameInit) {
        GameInit.Race race = gameInit.race;
        track = race.track;
        carsGeometricInfos = race.cars;
        lapsCount = race.raceSession != null ? race.raceSession.laps : Integer.MAX_VALUE;
        setupMyCarGeometricInfo();
        powerDeficitsByPiece = new MedianFinder[track.pieces.length];
        powerDeficitsByPieceBuf = new IntDouble[track.pieces.length];
        for (int i = 0; i < track.pieces.length; i++) {
            powerDeficitsByPiece[i] = new MedianFinder();
            powerDeficitsByPieceBuf[i] = new IntDouble();
        }
//        System.out.println("race inited, track " + race.track.name);
//        if (track.lanes.length > 1) {
//            double firstLaneOffs = track.lanes[0].distanceFromCenter;
//            double secondLaneOffs = track.lanes[1].distanceFromCenter;
//            for (int pieceIndex = 0; pieceIndex < race.track.pieces.length; pieceIndex++) {
//                GameInit.Race.Track.Piece piece = race.track.pieces[pieceIndex];
//                System.out.format(pieceIndex + " piece len %8.1f %8.1f R %8.1f anglePredictorB %8.1f have switch " + piece.haveSwitch + "\n",
//                        piece.getLaneLength(firstLaneOffs, firstLaneOffs), piece.getLaneLength(secondLaneOffs, secondLaneOffs), piece.radius, piece.angle);
//            }
//        }
    }

    public void setCarPositions(ArrayList<CarPosition> carPositions) {
        allCars2.clear();
        for (CarPosition carPosition : carPositions) {
            CarInfo carInfoOld = null;
            for (CarInfo car : allCars) {
                if (car.color != null && car.color.equals(carPosition.color)) {
                    carInfoOld = car;
                }
            }
            CarInfo newCarInfo = obtainCarInfo(carPosition.name, carPosition.color, carInfoOld, carPosition);
            allCars2.add(newCarInfo);
            if (carInfoOld != null) {
                recycledCarInfos.add(carInfoOld);
            }
        }
        ArrayList<CarInfo> t = allCars;
        allCars = allCars2;
        allCars2 = t;
        if (turboTicksLeft > 0) {
            turboTicksLeft--;
            if (turboTicksLeft == 0) {
                System.out.println("set turbo factor to 1 because no more turboTicks left");
                turboFactor = 1;
            }
        }
        if (turboCautionTicksLeft > 0) {
            turboCautionTicksLeft--;
        }
    }

    public CarInfo getMyCarInfo() {
        for (CarInfo car : allCars) {
            if (car.color != null && car.color.equals(myCarColor)) {
                return car;
            }
        }
        return null;
    }

    public void setTurboFactor(double turboFactor, int ticksToLast) {
        this.turboFactor = turboFactor;
        if (turboFactor < 1.001 && turboTicksLeft != 1) {
            System.out.println("set no turbo while turboTicksLeft is " + turboTicksLeft);
        }
        this.turboTicksLeft = ticksToLast > 0 ? ticksToLast + 2 : 0;
        if (ticksToLast > 0) {
            this.turboCautionTicksLeft = ticksToLast * 2;
        }
    }

    public void onCrash() {
        safetyAngle += 2;
        if (safetyAngle > 30) {
            safetyAngle = 30;
        }
        System.out.println("new safety angle " + safetyAngle);
    }

    public void onLapFinish(boolean hadCrash) {
        if (!hadCrash) {
            if (safetyAngle > 1) {
                safetyAngle -= 1;
            } else {
                safetyAngle = MIN_SAFETY_ANGLE;
            }
            System.out.println("new safety angle " + safetyAngle);
        }
    }

    public double getTurboFactor() {
        return turboFactor;
    }

    public double lookAroundGetDistanceToANearByVehicle() {
        CarInfo myCarInfo = getMyCarInfo();
        int myPieceIndex = myCarInfo.pieceIndex;
        int nextPieceIndex = getNextPieceIndex(myPieceIndex);
        int prevPieceIndex = getPrevPieceIndex(myPieceIndex);
        double prevPieceLength = getLaneLength(track.pieces[prevPieceIndex], track.lanes[myCarInfo.startLaneIndex].distanceFromCenter, track.lanes[myCarInfo.startLaneIndex].distanceFromCenter);
        double currPieceLength = getLaneLength(track.pieces[myPieceIndex], track.lanes[myCarInfo.startLaneIndex].distanceFromCenter, track.lanes[myCarInfo.endLaneIndex].distanceFromCenter);
        double minDist = Double.MAX_VALUE;
        double minDistRear = -Double.MAX_VALUE;
        double minDistFront = Double.MAX_VALUE;
        nearestCarName = nearestCarFrontName = nearestCarRearName = "";
        nearestCarFrontDist = nearestCarRearDist = Double.MAX_VALUE;
        nearestCarFrontDistDeltaVelocity = nearestCarRearDistDeltaVelocity = 0;
        nearestCarFrontTimeToCollision = nearestCarRearTimeToCollision = Double.MAX_VALUE;
        for (CarInfo carInfo : allCars) {
            if (!dnfColors.contains(carInfo.color) && !crashedCars.contains(carInfo.color)) {
                double dist = 0;
                if (carInfo.pieceIndex == myPieceIndex && (carInfo.endLaneIndex == myCarInfo.endLaneIndex || carInfo.startLaneIndex == myCarInfo.startLaneIndex)) {
                    dist = carInfo.inPieceDistance - myCarInfo.inPieceDistance;
                } else if (carInfo.pieceIndex == prevPieceIndex && carInfo.endLaneIndex == myCarInfo.startLaneIndex) {
                    dist = carInfo.inPieceDistance - prevPieceLength - myCarInfo.inPieceDistance;
                } else if (carInfo.pieceIndex == nextPieceIndex && carInfo.startLaneIndex == myCarInfo.endLaneIndex) {
                    dist = carInfo.inPieceDistance + currPieceLength - myCarInfo.inPieceDistance;
                }
                double absDist = Math.abs(dist);
                if (absDist > 0.00001) {
                    if (absDist < Math.abs(minDist)) {
                        minDist = dist;
                        nearestCarName = carInfo.name;
                        nearestCarEndLineIndex = carInfo.endLaneIndex;
                    }
                    if (myCarGeometricInfo != null && myCarGeometricInfo.dimensions != null) {
                        if (dist < 0 && dist > minDistRear) {
                            minDistRear = dist;
                            nearestCarRearName = carInfo.name;
                            nearestCarRearDist = -dist - myCarGeometricInfo.dimensions.length;
                            if (nearestCarRearDist < 0) {
                                System.out.println("rear crumpling? " + nearestCarRearDist + " car " + nearestCarRearName + " lane " + carInfo.startLaneIndex + "-" + carInfo.endLaneIndex +
                                        ", mine lane " + myCarInfo.startLaneIndex + "-" + myCarInfo.endLaneIndex + " my pieceIndex " + myCarInfo.pieceIndex + " their " + carInfo.pieceIndex);
                            }
                            nearestCarRearDistDeltaVelocity = carInfo.velocity - myCarInfo.velocity;
                            nearestCarRearTimeToCollision = nearestCarRearDist / nearestCarRearDistDeltaVelocity;
                        }
                        if (dist > 0 && dist < minDistFront) {
                            minDistFront = dist;
                            nearestCarFrontName = carInfo.name;
                            nearestCarFrontDist = dist - myCarGeometricInfo.dimensions.length;
                            if (nearestCarFrontDist < 0) {
                                System.out.println("front crumpling? " + nearestCarFrontDist + " car " + nearestCarFrontName + " lane " + carInfo.startLaneIndex + "-" + carInfo.endLaneIndex +
                                        ", mine lane " + myCarInfo.startLaneIndex + "-" + myCarInfo.endLaneIndex + " my pieceIndex " + myCarInfo.pieceIndex + " their " + carInfo.pieceIndex);
                            }
                            nearestCarFrontDistDeltaVelocity = myCarInfo.velocity - carInfo.velocity;
                            nearestCarFrontTimeToCollision = nearestCarFrontDist / nearestCarFrontDistDeltaVelocity;
                        }
                    }
                }
            }
        }
        return minDist;
    }

    public double getMyCarLength() {
        return myCarGeometricInfo == null || myCarGeometricInfo.dimensions == null ? 50 : myCarGeometricInfo.dimensions.length;
    }

    public void updatePowerDeficitForPiece(int pieceIndex) {
        powerDeficitsByPiece[pieceIndex].add(powerDeficit);
    }

    public void clearDisqualifiedCarsList() {
        dnfColors.clear();
    }

    public void setQisqualified(String color) {
        dnfColors.add(color);
    }

    public void clearCrashedCarsList() {
        crashedCars.clear();
    }

    public void setCrashedCar(String color, boolean crashed) {
        if (crashed) {
            crashedCars.add(color);
        } else {
            crashedCars.remove(color);
        }
    }

    private static class IntDouble {
        int index;
        double value;
    }

    public boolean isItAGoodPieceForTurbo(int lap, int pieceIndex) {
        final int k = 2;
        double piecePowerDefValue;
        if (lastTurboDeficitPieceIndexUpdate != pieceIndex || lastTurboDeficitLapUpdate != lap) {
            lastTurboDeficitPieceIndexUpdate = pieceIndex;
            lastTurboDeficitLapUpdate = lap;
            for (int i = 0; i < powerDeficitsByPieceBuf.length; i++) {
                powerDeficitsByPieceBuf[i].index = i;
                powerDeficitsByPieceBuf[i].value = powerDeficitsByPiece[i].getMedian();
            }
            piecePowerDefValue = powerDeficitsByPieceBuf[pieceIndex].value;
            int from = 0, to = powerDeficitsByPieceBuf.length - 1;
            while (from < to) {
                int r = from, w = to;
                double mid = powerDeficitsByPieceBuf[(r + w) / 2].value;
                while (r < w) {
                    if (powerDeficitsByPieceBuf[r].value <= mid) {
                        IntDouble tmp = powerDeficitsByPieceBuf[w];
                        powerDeficitsByPieceBuf[w] = powerDeficitsByPieceBuf[r];
                        powerDeficitsByPieceBuf[r] = tmp;
                        w--;
                    } else {
                        r++;
                    }
                }
                if (powerDeficitsByPieceBuf[r].value > mid) {
                    r--;
                }
                if (k <= r) {
                    to = r;
                } else {
                    from = r + 1;
                }
            }
        } else {
            piecePowerDefValue = powerDeficitsByPiece[pieceIndex].getMedian();
        }
        if (powerDeficitsByPieceBuf[k].value <= piecePowerDefValue) {
            System.out.println("turbo approved because " + k + " value is " + powerDeficitsByPieceBuf[k].value + "<" + piecePowerDefValue);
        }
        return powerDeficitsByPieceBuf[k].value <= piecePowerDefValue;
    }


    public class CarInfo {
        public String name;
        public String color;
        public double angle;
        public double totalDist;
        public double currentLaneLength;
        public int prevCommandTick;
        public double velocity;
        public double acceleration;
        public int pieceIndex;
        public double inPieceDistance;
        public int startLaneIndex;
        public int endLaneIndex;
        public int lap;

        protected CarInfo() {
        }

        public void update(String name, String color, CarInfo carInfoOld, CarPosition carPosition) {
            this.name = name;
            this.color = color;
            this.pieceIndex = carPosition.pieceIndex;
            this.inPieceDistance = carPosition.inPieceDistance;
            this.startLaneIndex = carPosition.startLaneIndex;
            this.endLaneIndex = carPosition.endLaneIndex;
            this.lap = carPosition.lap;
            this.angle = carPosition.angle;
            this.prevCommandTick = carPosition.prevCommandTick;
            currentLaneLength = getLaneLength(
                    track.pieces[pieceIndex],
                    track.lanes[startLaneIndex].distanceFromCenter,
                    track.lanes[endLaneIndex].distanceFromCenter);

            if (carInfoOld == null) {
                velocity = 0;
                totalDist = 0;
            } else {
                if (pieceIndex == carInfoOld.pieceIndex) {
                    velocity = inPieceDistance - carInfoOld.inPieceDistance;
                } else {
                    velocity = inPieceDistance + carInfoOld.currentLaneLength - carInfoOld.inPieceDistance;
                }
                totalDist = carInfoOld.totalDist + velocity;
            }
            acceleration = velocity - (carInfoOld == null ? 0 : carInfoOld.velocity);

        }

        public double getRAcceleration() {
            GameInit.Race.Track.Piece trackPiece = track.pieces[pieceIndex];
            if (trackPiece.length > 0) {
                return 0;
            }
            int laneIndex = inPieceDistance > 0.5f * currentLaneLength ? endLaneIndex : startLaneIndex;
            double currentLaneRadius = trackPiece.getRadius(track.lanes[laneIndex].distanceFromCenter);
            double rAccell = velocity * velocity / currentLaneRadius;
            if (track.pieces[pieceIndex].angle < 0) {
                rAccell = -rAccell;
            }
            return rAccell;
        }

        public double getR() {
            GameInit.Race.Track.Piece trackPiece = track.pieces[pieceIndex];
            double distanceFromCenterStart = track.lanes[startLaneIndex].distanceFromCenter;
            double distanceFromCenterEnd = track.lanes[endLaneIndex].distanceFromCenter;
            return getR(track.pieces[pieceIndex],
                    distanceFromCenterStart,
                    distanceFromCenterEnd,
                    inPieceDistance / getLaneLength(trackPiece, distanceFromCenterStart, distanceFromCenterEnd));
        }

        public double getR(GameInit.Race.Track.Piece trackPiece, double distanceFromCenterStart, double distanceFromCenterEnd, double pieceOffsRelative) {
//            GameInit.Race.Track.Piece trackPiece = track.pieces[piecePosition.pieceIndex];
            if (trackPiece.length > 0) {
                return Double.MAX_VALUE;
            }
            double currentLaneRadius;
            if (Math.abs(distanceFromCenterStart - distanceFromCenterEnd) < 0.0001) {
                currentLaneRadius = trackPiece.getRadius(distanceFromCenterStart);
            } else {
                if (trackPiece.length <= 0) {
                    double rStart = trackPiece.getRadius(distanceFromCenterStart);
                    double rEnd = trackPiece.getRadius(distanceFromCenterEnd);
                    long key = getBumpKey(trackPiece.angle, rStart, rEnd);
                    SwitchRadiusInfo[] switchesInfo = weirdBendedSwitchesInfo.get(key);
                    if (switchesInfo != null && switchesInfo.length > 0) {
                        int insertionPoint = Arrays.binarySearch(switchesInfo, new SwitchRadiusInfo(0, pieceOffsRelative));
                        if (insertionPoint >= 0) {
//                            System.out.println("hite " + trackPiece.angle + " " + distanceFromCenterStart + " " + distanceFromCenterEnd + " key " + key);
                            return switchesInfo[insertionPoint].r;
                        } else {
                            insertionPoint = -insertionPoint;
                            double distToLeft, distToRight;
                            int indexLeft, indexRight;
                            if (insertionPoint == 0) {
                                if (switchesInfo.length >= 2) {
                                    indexLeft = insertionPoint;
                                    distToLeft = Math.abs(switchesInfo[indexLeft].pieceOffsRelative - pieceOffsRelative);
                                    indexRight = insertionPoint + 1;
                                    distToRight = Math.abs(switchesInfo[indexRight].pieceOffsRelative - pieceOffsRelative);
                                } else if (switchesInfo.length == 1) {
                                    indexLeft = 0;
                                    distToLeft = Math.abs(switchesInfo[indexLeft].pieceOffsRelative - pieceOffsRelative);
                                    indexRight = -1;
                                    distToRight = Double.MAX_VALUE;
                                } else {
                                    indexLeft = indexRight = -1;
                                    distToLeft = distToRight = Double.MAX_VALUE;
                                }
                            } else if (insertionPoint >= switchesInfo.length) {
                                if (insertionPoint > switchesInfo.length) {
                                    insertionPoint = switchesInfo.length;//wtf?
                                }
                                if (switchesInfo.length >= 2) {
                                    indexLeft = insertionPoint - 2;
                                    distToLeft = Math.abs(switchesInfo[indexLeft].pieceOffsRelative - pieceOffsRelative);
                                    indexRight = insertionPoint - 1;
                                    distToRight = Math.abs(switchesInfo[indexRight].pieceOffsRelative - pieceOffsRelative);
                                } else if (switchesInfo.length == 1) {
                                    indexLeft = 0;
                                    distToLeft = Math.abs(switchesInfo[indexLeft].pieceOffsRelative - pieceOffsRelative);
                                    indexRight = -1;
                                    distToRight = Double.MAX_VALUE;
                                } else {
                                    indexLeft = indexRight = -1;
                                    distToLeft = distToRight = Double.MAX_VALUE;
                                }
                            } else {
                                indexLeft = insertionPoint - 1;
                                distToLeft = Math.abs(switchesInfo[indexLeft].pieceOffsRelative - pieceOffsRelative);
                                indexRight = insertionPoint;
                                distToRight = Math.abs(switchesInfo[indexRight].pieceOffsRelative - pieceOffsRelative);
                            }
                            if (distToRight < distToLeft) {
                                if (distToRight < 0.2) {
//                                    System.out.println("hitr " + trackPiece.angle + " " + rStart + " " + rEnd + " key " + key + " val " + switchesInfo[indexRight].r + " for " + (switchesInfo[indexRight].pieceOffsRelative - pieceOffsRelative));
                                    return switchesInfo[indexRight].r;
                                }
                            } else {
                                if (distToLeft < 0.2) {
//                                    System.out.println("hitl " + trackPiece.angle + " " + rStart + " " + rEnd + " key " + key + " val " + switchesInfo[indexLeft].r + " for " + (switchesInfo[indexLeft].pieceOffsRelative - pieceOffsRelative));
                                    return switchesInfo[indexLeft].r;
                                }
                            }
                        }
                    }
//                    else {
//                        System.out.println("miss " + trackPiece.angle + " " + distanceFromCenterStart + " " + distanceFromCenterEnd + " key " + key);
//                    }
                    currentLaneRadius = trackPiece.getRadius(besierSwitch(pieceOffsRelative, distanceFromCenterStart, distanceFromCenterEnd));
                } else {
                    currentLaneRadius = Double.MAX_VALUE;
                }
            }

            if (trackPiece.angle < 0) {
                currentLaneRadius = -currentLaneRadius;
            }
            return currentLaneRadius;
        }
    }

    private static double besierSwitch(double t, double start, double end) {
        double midpoint = (start + end) / 2;
        t *= 2;
        if (t < 1) {
            return midpoint * t * t + start * 2 * t * (1 - t) + start * (1 - t) * (1 - t);
        } else {
            t -= 1;
            return end * t * t + end * 2 * t * (1 - t) + midpoint * (1 - t) * (1 - t);
        }
    }

//    private static double besierSwitch3(double t, double start, double end) {
//        double midpoint = (start + end) / 2;
//        t *= 2;
//        if (t < 1) {
//            return midpoint * t * t * t + start * 3 * t * t * (1 - t) + start * 3 * t * (t - 1) * (1 - t) + start * (1 - t) * (1 - t) * (1 - t);
//        } else {
//            t -= 1;
//            return end * t * t * t + end * 3 * t * t * (1 - t) + end * 3 * t * (1 - t) * (1 - t) + midpoint * (1 - t) * (1 - t) * (1 - t);
//        }
//    }


    private static class Obstruction {
        final double pieceLen;
        final int laneIndex;
        final int pieceIndex;
        final double inPieceDistance;
        final double velocity;
        final String name;

        private Obstruction(int laneIndex, int pieceIndex, double inPieceDistance, double velocity, double pieceLen, String name) {
            this.name = name;
            this.pieceIndex = pieceIndex;
            this.inPieceDistance = inPieceDistance;
            this.velocity = velocity;
            this.pieceLen = pieceLen;
            this.laneIndex = laneIndex;
        }
    }

    public LaneSwitch.Options whereWeShouldTurn(AngleState angleState, double lastThrottle) {
        CarInfo myCarInfo = getMyCarInfo();
        int currentLane = myCarInfo.endLaneIndex;
        int currentPieceIndex = myCarInfo.pieceIndex;

        double distanceFromCenter = track.lanes[currentLane].distanceFromCenter;
        double currentLaneLen = getLaneLength(track.pieces[currentPieceIndex], distanceFromCenter, distanceFromCenter);
        int nextPieceIndex = getNextPieceIndex(currentPieceIndex);
        double nextLaneLen = getLaneLength(track.pieces[nextPieceIndex], distanceFromCenter, distanceFromCenter);
        double distLeftToSwitch = currentLaneLen - myCarInfo.inPieceDistance + nextLaneLen / 2;
        double timeToSwitch = distLeftToSwitch / myCarInfo.velocity;

        ArrayList<Obstruction> obstructions = new ArrayList<>(allCars.size());
        for (CarInfo carInfo : allCars) {
            if (!carInfo.color.equals(myCarColor) && !dnfColors.contains(carInfo.color)) {
                double offs = carInfo.velocity * timeToSwitch;
                int carPieceIndex = carInfo.pieceIndex;
                double inPieceDistance = carInfo.inPieceDistance;
                double pieceLen;
                while (true) {
                    pieceLen = getLaneLength(track.pieces[carPieceIndex], distanceFromCenter, distanceFromCenter);
                    double distLeftToNextSector = pieceLen - inPieceDistance;
                    if (offs > distLeftToNextSector) {
                        offs -= distLeftToNextSector;
                        carPieceIndex = getNextPieceIndex(carPieceIndex);
                        inPieceDistance = 0;
                    } else {
                        inPieceDistance += offs;
                        break;
                    }
                }
                obstructions.add(new Obstruction(carInfo.endLaneIndex, carPieceIndex, inPieceDistance, carInfo.velocity, pieceLen, carInfo.name));
            }
        }


        int bestLaneIndex = -1;
        double bestScore = Double.MAX_VALUE;
        ArrayList<Double> ticks = new ArrayList<>(3);
        ArrayList<Double> ticksPred = new ArrayList<>(3);
        for (int laneIndex = Math.max(0, currentLane - 1); laneIndex <= Math.min(track.lanes.length - 1, currentLane + 1); laneIndex++) {//you can switch right, left or make no switch
            double laneDistanceFromCenter = track.lanes[laneIndex].distanceFromCenter;
            double myPieceOffs = 0;
            double totalPenalty = 0;
            double totalLen = 0;
            for (int pieceIndex = nextPieceIndex, switchesLeft = 2; ; pieceIndex = getNextPieceIndex(pieceIndex)) {
                boolean currentPieceHaveSwitch = track.pieces[pieceIndex].haveSwitch;
                if (currentPieceHaveSwitch) {
                    if (switchesLeft == 0) {
                        break;
                    }
                    switchesLeft--;
                }
                double pieceLength = getLaneLength(track.pieces[pieceIndex], laneDistanceFromCenter, laneDistanceFromCenter);
                totalLen += pieceLength;
                if (pieceIndex == nextPieceIndex && currentPieceHaveSwitch) {
                    myPieceOffs = pieceLength / 2;
                } else {
                    myPieceOffs -= pieceLength;
                }
                for (Obstruction obstruction : obstructions) {
                    if (obstruction.pieceIndex == pieceIndex && obstruction.laneIndex == laneIndex &&
                            obstruction.inPieceDistance > myPieceOffs && myCarInfo.velocity > obstruction.velocity) {
                        double timeToCollision = (obstruction.inPieceDistance - myPieceOffs) / (myCarInfo.velocity - obstruction.velocity);
                        double penalty = (5000 + random.nextInt(10000)) / timeToCollision;
                        System.out.println("      timeToCollision " + timeToCollision + " dist " + (obstruction.inPieceDistance - myPieceOffs) + " vel " + (myCarInfo.velocity - obstruction.velocity) + " penalty " + penalty + " lane " + laneIndex + " car " + obstruction.name);
                        totalPenalty += penalty;
                    }
                }
            }
            totalPenalty += totalLen;
            if (laneIndex == currentLane) {
                totalPenalty += predictedTicksToRideTheDistance - 1;
                if (track.pieces[nextPieceIndex].length < 0.0000001) {
                    totalPenalty -= SWITCH_PENALTY_FOR_BENDED_PIECE;
                }
                ticksPred.add(predictedTicksToRideTheDistance - 1);
            } else {
                getDesiredSpeed(myCarInfo, angleState, nextPieceIndex, laneIndex,
                        nearestCarRearDist, nearestCarRearDistDeltaVelocity,
                        nearestCarFrontDist, nearestCarFrontDistDeltaVelocity, lastThrottle);//TODO update nearestCarRearDist, nearestCarRearDistDeltaVelocity
                totalPenalty += predictedTicksToRideTheDistance;
                ticksPred.add(predictedTicksToRideTheDistance);
            }
            ticks.add(totalPenalty);
//            if (laneIndex != currentLane) {
//                totalPenalty -= random.nextInt(300);
//            }
            if (totalPenalty < bestScore) {
                bestScore = totalPenalty;
                bestLaneIndex = laneIndex;
            }
        }

        if (currentLane == bestLaneIndex || bestScore > 9000) {
            System.out.println("lane switcher: KEEP " + currentLane + Arrays.toString(ticks.toArray(new Double[ticks.size()])));
            return null;
        } else if (currentLane > bestLaneIndex) {
            System.out.println("lane switcher: SWITCH from " + currentLane + " to " + bestLaneIndex + " (LEFT) " +
                    Arrays.toString(ticks.toArray(new Double[ticks.size()])) + " predictor " + Arrays.toString(ticksPred.toArray(new Double[ticksPred.size()])));
            return LaneSwitch.Options.LEFT;
        } else {
            System.out.println("lane switcher: SWITCH from " + currentLane + " to " + bestLaneIndex + " (RIGHT) " +
                    Arrays.toString(ticks.toArray(new Double[ticks.size()])) + " predictor " + Arrays.toString(ticksPred.toArray(new Double[ticksPred.size()])));
            return LaneSwitch.Options.RIGHT;
        }


    }

    public int getNextPieceIndex(int pieceIndex) {
        int nextPieceIndex = pieceIndex + 1;
        if (nextPieceIndex >= track.pieces.length) {
            nextPieceIndex = 0;
        }
        return nextPieceIndex;
    }

    public int getPrevPieceIndex(int pieceIndex) {
        int prevPieceIndex = pieceIndex - 1;
        if (prevPieceIndex < 0) {
            prevPieceIndex = track.pieces.length - 1;
        }
        return prevPieceIndex;
    }

    public double getPowerDeficit() {
        return powerDeficit;
    }

    public double getDesiredSpeed(CarInfo car, AngleState angleState, int switchingLaneAtPieceIndex, int switchingLaneTo,
                                  double nearestCarRearDist, double nearestCarRearDistDeltaVelocity,
                                  double nearestCarFrontDist, double nearestCarFrontDistDeltaVelocity,
                                  double firstThrottle) {
        long start = System.nanoTime();
        //build initial desiredSpeeds array
        double dist = car.totalDist;
        int lap = car.lap;
        int pieceIndex = car.pieceIndex;
        double turboFactorForPrediction = getTurboFactor();
        int turboTicksLeftForPrediction = turboTicksLeft;
        int turboCautionTicksLeftForPrediction = turboCautionTicksLeft;
        double inPieceDistance = car.inPieceDistance;
        double laneDistanceFromCenterStart = track.lanes[car.startLaneIndex].distanceFromCenter;
        double laneDistanceFromCenterEnd = track.lanes[car.endLaneIndex].distanceFromCenter;
        GameInit.Race.Track.Piece trackPiece = track.pieces[pieceIndex];
        double currentPieceLen = getLaneLength(trackPiece, laneDistanceFromCenterStart, laneDistanceFromCenterEnd);
        double distToNextPiece = currentPieceLen - inPieceDistance;
        if (nearestCarRearDist > 25 || nearestCarRearDist < 0 || (nearestCarRearDist / nearestCarRearDistDeltaVelocity > 10 && nearestCarRearDist > 3)) {
            nearestCarRearDist = Double.MAX_VALUE;
        } else {
            if (nearestCarRearDist < 1 && nearestCarRearDistDeltaVelocity < 0) {
                nearestCarRearDistDeltaVelocity = 0.1;//he might change his mind
            }
            if (nearestCarRearDistDeltaVelocity > 0) {
                System.out.println("collision predicted in " + nearestCarRearDist / nearestCarRearDistDeltaVelocity + " force " + (nearestCarRearDistDeltaVelocity));
                if (anglePredictorCurrentError > 0.1 && dist < 3000) {
                    return MAX_SPEED;
                }
            }
        }
        if (nearestCarFrontDist > 25 || nearestCarFrontDist < 0 || (nearestCarFrontDist / nearestCarFrontDistDeltaVelocity > 10 && nearestCarFrontDist > 3)) {
            nearestCarFrontDist = Double.MAX_VALUE;
        } else {
            if (nearestCarFrontDistDeltaVelocity > 0) {
                System.out.println("frontal collision predicted in " + nearestCarFrontDist / nearestCarFrontDistDeltaVelocity + " force " + (nearestCarFrontDistDeltaVelocity));
                if (anglePredictorCurrentError > 0.1 && dist < 3000) {
                    return MIN_SPEED;
                }
            }
        }

        double initialRadius = car.getR();
        boolean initialStraight = Math.abs(initialRadius) > 100000;
        recycledRadiusChange.addAll(radiuses);
        radiuses.clear();
        radiuses.add(obtainRadiusChange(0, initialRadius));
        double desiredSpeed = initialRadius > 200000 ? MAX_SPEED : Math.min(MAX_SPEED, Math.sqrt(MAX_RACCEL * Math.abs(initialRadius)));
        for (int i = 0; i < distToNextPiece; i++) {
            desiredSpeeds[i] = desiredSpeed;
            if (desiredSpeed != desiredSpeed) {
                System.out.println("nan");
            }
            if (!initialStraight && switchingLaneAtPieceIndex == pieceIndex && (i % 5) == 4) {
                radiuses.add(obtainRadiusChange(dist + i, car.getR(trackPiece, laneDistanceFromCenterStart, laneDistanceFromCenterEnd,
                        (inPieceDistance + i) / currentPieceLen)));
            }
        }
        double laneChangeDistStart = 0;
        double laneChangeDistEnd = 0;
        if (switchingLaneAtPieceIndex == pieceIndex) {
            laneChangeDistStart = dist;
            laneChangeDistEnd = dist + currentPieceLen;
            laneDistanceFromCenterStart = laneDistanceFromCenterEnd;
        }
        dist += distToNextPiece;
        final double maxDist = car.totalDist + desiredSpeeds.length;

        while (dist < maxDist) {
            pieceIndex = getNextPieceIndex(pieceIndex);
            if (pieceIndex == 0) {
                if (lap >= 0) {
                    lap++;
                }
            }

            if (lap >= lapsCount && lapsCount >= 1) {
                trackPiece = track.pieces[pieceIndex];
//                radius = Double.MAX_VALUE;
                desiredSpeed = MAX_SPEED;
                currentPieceLen = getLaneLength(trackPiece, laneDistanceFromCenterStart, laneDistanceFromCenterEnd);
                int maxIndex = (int) (dist - car.totalDist + currentPieceLen);
                for (int i = (int) (dist - car.totalDist); i < maxIndex && i < desiredSpeeds.length; i++) {
                    if (i >= 0) {
                        desiredSpeeds[i] = desiredSpeed;
                    }
                }
                radiuses.add(obtainRadiusChange(dist, Double.MAX_VALUE));
                dist += currentPieceLen;
            } else {
                trackPiece = track.pieces[pieceIndex];
                double pieceRadiusEnd = trackPiece.getRadius(laneDistanceFromCenterEnd);
                desiredSpeed = pieceRadiusEnd > 100000 ? MAX_SPEED : Math.min(MAX_SPEED, Math.sqrt(MAX_RACCEL * pieceRadiusEnd));

                currentPieceLen = getLaneLength(trackPiece, laneDistanceFromCenterStart, laneDistanceFromCenterEnd);
                int maxIndex = (int) ((dist - car.totalDist + currentPieceLen));
                if (trackPiece.angle < 0) {
                    pieceRadiusEnd = -pieceRadiusEnd;
                }
                boolean straight = Math.abs(pieceRadiusEnd) > 100000;
                for (int i = (int) ((dist - car.totalDist)); i < maxIndex && i < desiredSpeeds.length; i++) {
                    if (i >= 0) {
                        desiredSpeeds[i] = desiredSpeed;
                    }
                }

                if (switchingLaneAtPieceIndex == pieceIndex && laneChangeDistStart < 0.01) {//only single switch is supported
                    laneDistanceFromCenterEnd = track.lanes[switchingLaneTo].distanceFromCenter;
                    laneChangeDistStart = dist;
                    laneChangeDistEnd = dist + currentPieceLen;
                    if (!straight) {
                        int extraPointsForBendedSwitch = 10;
                        for (int i = 1; i < extraPointsForBendedSwitch; i++) {
                            double pieceOffsRelative = ((double) i) / extraPointsForBendedSwitch;
                            double pieceRadius = car.getR(trackPiece, laneDistanceFromCenterStart, laneDistanceFromCenterEnd, pieceOffsRelative);
                            radiuses.add(obtainRadiusChange(dist + i * (currentPieceLen / extraPointsForBendedSwitch), pieceRadius));
                        }
                    }
                    radiuses.add(obtainRadiusChange(dist, pieceRadiusEnd));
                    laneDistanceFromCenterStart = laneDistanceFromCenterEnd;
                } else {
                    if (radiuses.isEmpty() || Math.abs(radiuses.get(radiuses.size() - 1).radius - pieceRadiusEnd) > 0.0001) {
                        radiuses.add(obtainRadiusChange(dist, pieceRadiusEnd));//radius at start actually, but it's same at end and start here
                    }
                }
                dist += currentPieceLen;
            }
        }

        //simulate
        double speed = car.velocity;
        emulatedAngleState.copy(angleState);
        powerDeficit = 0;
        double nearestCarRearDistForEmulation = nearestCarRearDist;
        double nearestCarFrontDistForEmulation = nearestCarFrontDist;
        boolean rearCollisionHappened = false;
        int collectingPowerDeficitCounter = 30;
        ArrayList<String> debugBuf = null;
        desiredSpeedIncreasesCount = desiredSpeedDecreasesCount = 0;
        double crashAngle = getCrashAngle();
        final double realMinRAccell = (anglePredictorMinRAccell / anglePredictorA) * (anglePredictorMinRAccell / anglePredictorA);
        predictedTicksToRideTheDistance = 0;
        for (dist = car.totalDist; /*dist < maxDist*/ ; dist += speed) {
            int i = (int) (dist - car.totalDist);
            if (i >= desiredSpeeds.length) {
                i = desiredSpeeds.length - 1;
            }
            double deltaPowerDeficit = desiredSpeeds[i] - speed;
            if (deltaPowerDeficit > 0 && collectingPowerDeficitCounter > 0) {
                powerDeficit += deltaPowerDeficit;
                collectingPowerDeficitCounter--;
            } else {
                collectingPowerDeficitCounter = 0;
            }

            double throttle = firstThrottle >= 0 && dist == car.totalDist ? firstThrottle : getThrottle(speed, desiredSpeeds[i]);
            if (turboTicksLeftForPrediction > 0) {
                turboTicksLeftForPrediction--;
                if (turboTicksLeftForPrediction == 0) {
                    turboFactorForPrediction = 1;
                }
            }
            if (turboCautionTicksLeftForPrediction > 0) {
                turboCautionTicksLeftForPrediction--;
            }
            double emulatedTickRadius = getRadius(radiuses, dist);
            if (debug) {
                debugBuf.add(String.format(" FORECAST dist %9.4f, speed %9.6f, racc %6.3f, angle %10.6f throttle %4.1f, desSpeed %6.2f pieceradius %6.1f ind %2d next speed %9.6f angleder %9.6f turbo %7.1f", dist, speed, speed * speed / emulatedTickRadius,
                        emulatedAngleState.angle, throttle, desiredSpeeds[i], Math.abs(emulatedTickRadius) > 1000 ? 0 : emulatedTickRadius, i, getNextSpeed(speed, throttle * turboFactorForPrediction), emulatedAngleState.angleDer, turboFactorForPrediction));
            }
            double extraPrecautionOnLaneSwitch = dist >= laneChangeDistStart && dist <= laneChangeDistEnd ? EXTRA_SAFETY_ANGLE_FOR_LANE_SWITCHING : 0;
            double extraPrecautionOnTurbo = turboCautionTicksLeftForPrediction > 0 ? EXTRA_SAFETY_ANGLE_FOR_TURBO : 0;
            if (Math.abs(emulatedAngleState.angle) >= crashAngle - extraPrecautionOnLaneSwitch - extraPrecautionOnTurbo) {
                if ((System.nanoTime() - start > 8000000) || ((System.nanoTime() - start > 3000000 && car.totalDist < 2000))) {
                    System.out.println("time budget exceed for getDesiredSpeed on " + desiredSpeedDecreasesCount + " iteration train " + (car.totalDist < 2500));
                    predictedTicksToRideTheDistance = 5000;
                    return MIN_SPEED + 1;
                }
                desiredSpeedDecreasesCount++;
                double crashPoint = dist;

                boolean decreasedSome = false;
                int iAtCrash = i;
                for (; i >= 0; i--) {
                    dist = i + car.totalDist;
                    speed = desiredSpeeds[i];
                    double radiusForBackCrashTick = Math.abs(getRadius(radiuses, dist));
                    double rAccell = speed * speed / radiusForBackCrashTick;
                    if (rAccell > realMinRAccell) {
                        decreasedSome = true;
                        double deltaRacc = (rAccell - realMinRAccell) * DECREASE_RACC_K;
                        double newRAccel = deltaRacc < 0.01 ? (realMinRAccell - 0.0001) : (rAccell - deltaRacc);//decrease force _what makes angle change_ at crash point.
                        speed = Math.sqrt(newRAccel * radiusForBackCrashTick);
                        desiredSpeeds[i] = speed;
                        while (dist > 0) {
                            speed = speed / Math.exp(-drag / mass);
                            if (speed < MIN_SPEED) {
                                speed = MIN_SPEED;
                            }
                            dist -= speed;
                            int newIndex = (int) (dist - car.totalDist);
                            while (--i >= newIndex && i >= 0) {
                                if (desiredSpeeds[i] >= speed) {
                                    desiredSpeeds[i] = speed;
                                }
                            }
                            i = newIndex;
                        }
                        break;
                    }
                }
                if (!decreasedSome) {
                    //okay, braking doesn't help, let's try acceleration!
                    boolean increasedSome = false;
                    i = iAtCrash;
                    for (; i >= 0; i--) {
                        dist = i + car.totalDist;
                        if (Math.abs(crashPoint - car.totalDist) > 200) {
                            break;
                        }
                        if (Math.abs(getRadius(radiuses, dist)) > 100000 && desiredSpeeds[i] < MAX_SPEED) {
                            desiredSpeeds[i] = desiredSpeeds[i] * 1.2;
                            increasedSome = true;
                        }
                    }

                    if (!increasedSome) {
                        System.out.println("SOS!!! crash at " + crashPoint + " it is + " + (crashPoint - car.totalDist) + " " + (getCrashAngle() - extraPrecautionOnLaneSwitch - extraPrecautionOnTurbo) +
                                " deg extraPrecautionOnTurbo " + extraPrecautionOnTurbo + " turboCautionTicksLeftForPrediction " + turboCautionTicksLeftForPrediction + " desiredSpeedIncreasesCount " + desiredSpeedIncreasesCount + " des speed " + desiredSpeeds[0]);
                        if (debug) {
                            for (String forecast : debugBuf) {
                                System.out.println(forecast);
                            }
                        }
                        predictedTicksToRideTheDistance = 10000;
                        return MIN_SPEED;
                    } else {
                        desiredSpeedIncreasesCount++;
                    }
                }
                speed = car.velocity;
                turboFactorForPrediction = getTurboFactor();
                turboTicksLeftForPrediction = turboTicksLeft;
                turboCautionTicksLeftForPrediction = turboCautionTicksLeft;

                emulatedAngleState.copy(angleState);
                dist = car.totalDist - car.velocity;
                powerDeficit = 0;
                collectingPowerDeficitCounter = 30;
                if (debug) {
                    debugBuf.clear();
                    debugBuf.add(String.format(" FORECAST start from dist %9.4f, speed %6.2f, angle %10.6f inputangle %9.6f angleDer %9.8f crashangle %9.8f prevcrashpoint %9.8f", dist, speed, emulatedAngleState.angle, angleState.angle, angleState.angleDer, getCrashAngle(), crashPoint));
                }
                predictedTicksToRideTheDistance = 0;
                nearestCarRearDistForEmulation = nearestCarRearDist;
                nearestCarFrontDistForEmulation = nearestCarFrontDist;
                rearCollisionHappened = false;
                continue;
            }
            emulatedAngleState.update(speed, emulatedTickRadius);
            speed = getNextSpeed(speed, throttle * turboFactorForPrediction);
            if (nearestCarRearDistDeltaVelocity > 0 && nearestCarRearDistDeltaVelocity < 10) {
                nearestCarRearDistForEmulation -= nearestCarRearDistDeltaVelocity;
            }
            if (nearestCarFrontDistDeltaVelocity > 0 && nearestCarFrontDistDeltaVelocity < 10) {
                nearestCarFrontDistForEmulation -= nearestCarFrontDistDeltaVelocity;
            }
//            if (rearCollisionHappened) {
//                rearCollisionHappened = false;
//                speed -= nearestCarRearDistDeltaVelocity;//aftershock
//            }
            if (nearestCarRearDistForEmulation < 0) {
                speed += nearestCarRearDistDeltaVelocity;
                nearestCarRearDistForEmulation = Double.MAX_VALUE;
//                rearCollisionHappened = true;
            }
            if (nearestCarFrontDistForEmulation < 0) {
                speed -= nearestCarFrontDistDeltaVelocity;
                nearestCarFrontDistForEmulation = Double.MAX_VALUE;
            }
            if (speed < MIN_SPEED) {
                speed = MIN_SPEED;
            }
            double distLeft = maxDist - dist - speed;
            if (distLeft > 0) {
                predictedTicksToRideTheDistance += 1;
            } else {
                predictedTicksToRideTheDistance += (speed + distLeft) / speed;
                break;
            }
        }

        if (debug) {
            for (String forecast : debugBuf) {
                System.out.println(forecast);
            }
        }
        if (distanceDebugActive) {
            debugBuf = new ArrayList<>();
            debug = false;
        }
        if (lap != distanceDebugActiveLap) {
            distanceDebugActive = false;
        }
//        if (car.piecePosition.pieceIndex == 2 && !distanceDebugActive) {
//            distanceDebugActive = true;
//            debug = true;
//            distanceDebugActiveLap = lap;
//            System.out.println("request piece debug!");
//        }
//        if (car.piecePosition.pieceIndex == 3) {
//            distanceDebugActive = false;
//        }
        return desiredSpeeds[0];
    }

    private RadiusChange obtainRadiusChange(double dist, double radius) {
        RadiusChange radiusChange = !recycledRadiusChange.isEmpty() ? recycledRadiusChange.remove(recycledRadiusChange.size() - 1) : new RadiusChange();
        radiusChange.dist = dist;
        radiusChange.radius = radius;
        return radiusChange;
    }

    private CarInfo obtainCarInfo(String name, String color, CarInfo carInfoOld, CarPosition carPosition) {
        CarInfo carInfo = !recycledCarInfos.isEmpty() ? recycledCarInfos.remove(recycledCarInfos.size() - 1) : new CarInfo();
        carInfo.update(name, color, carInfoOld, carPosition);
        return carInfo;
    }


    private double getRadius(ArrayList<RadiusChange> radiuses, double dist) {
        for (int i = radiuses.size() - 1; i >= 0; i--) {
            if (radiuses.get(i).dist < dist) {
                return radiuses.get(i).radius;
            }
        }
        return Double.MAX_VALUE;
    }

    public double getNextSpeed(double currentSpeed, double throttle) {
        return (currentSpeed - throttle / drag) * Math.exp(-drag / mass) + throttle / drag;
    }

    public double getThrottle(double currentSpeed, double targetSpeed) {
        double edm = Math.exp(drag / mass);
        double throttle = (drag * (targetSpeed * edm - currentSpeed) / (edm - 1)) / getTurboFactor();
        if (throttle < 0) {
            throttle = 0;
        }
        if (throttle > 1) {
            throttle = 1;
        }
        return throttle;
    }

    public double getCrashAngle() {
        double crashAngle = MAX_ANGLE - safetyAngle;
        if (anglePredictorCurrentError > 0.1) {
            crashAngle -= Math.max(30, anglePredictorCurrentError);
        } else {
            if (anglePredictorPointsCount < 100) {
                crashAngle -= 5;
            }
            if (anglePredictorCurrentError > 0.001) {
                crashAngle -= 5;
            }
            crashAngle -= anglePredictorCurrentError;
        }
        return crashAngle;
    }

    private final FormulaFinder.Listener formulaFinderListener = new FormulaFinder.Listener() {
        private volatile double printedError = 100;

        @Override
        public synchronized void onResultsFound(int pointsCount, double minRAccell, double A, double B, double C, double error) {
            if (error < anglePredictorCurrentError) {
                if (printedError <= 0 || error / printedError < 0.5) {
                    printedError = error;
                    System.out.println("new params max err " + error + " deg: minRAcc " + minRAccell + " anglePredictorA " + A + " anglePredictorC " + C + " pointsCount " + pointsCount);
                }
                anglePredictorPointsCount = pointsCount;
                anglePredictorCurrentError = error;
                anglePredictorMinRAccell = minRAccell;
                anglePredictorA = A;
                anglePredictorB = B;
                anglePredictorC = C;
            }
        }
    };

    public boolean calcParameters(boolean lastSample, double[] speeds, double[] radiuses, double[] angles, int len) {
        double maxRealAngle = 0;
        double minRealAngle = 0;
        for (int i = 0; i < len; i++) {
            double angle = angles[i];
            if (angle > maxRealAngle) {
                maxRealAngle = angle;
            }
            if (angle < minRealAngle) {
                minRealAngle = angle;
            }
        }
        if (maxRealAngle - minRealAngle < (lastSample ? 1 : 10)) {
            System.out.println("not enough data: " + (maxRealAngle - minRealAngle) + " min " + minRealAngle + " max " + maxRealAngle);
            return false;
        }

        if (formulaFinder != null) {
            formulaFinder.stop();
        }

        if (angleState == Game.ANGLE_STATE_NEED_ALL_PARAMS) {
            double[] speedsCopy = new double[len];
            System.arraycopy(speeds, 0, speedsCopy, 0, len);
            double[] radiusesCopy = new double[len];
            System.arraycopy(radiuses, 0, radiusesCopy, 0, len);
            double[] anglesCopy = new double[len];
            System.arraycopy(angles, 0, anglesCopy, 0, len);
            formulaFinder = new FormulaFinder(speedsCopy, radiusesCopy, anglesCopy, formulaFinderListener);
            formulaFinder.setInitialParams(anglePredictorMinRAccell, anglePredictorA, anglePredictorB, anglePredictorC);
            formulaFinder.start();
        }
        return true;
    }

    public void stopFormulaFinder() {
        if (formulaFinder != null) {
            formulaFinder.stop();
            formulaFinder = null;
        }
    }


    @SuppressWarnings("NullableProblems")
    private static class RadiusChange implements Comparable<RadiusChange> {
        double dist;
        double radius;

        @Override
        public String toString() {
            return "RadiusChange{" +
                    "dist=" + dist +
                    ", radius=" + radius +
                    '}';
        }

        @Override
        public int compareTo(RadiusChange o) {
            return Double.compare(dist, o.dist);
        }
    }

    private static class RoadBump {
        final double size;
        final String description;

        public RoadBump(double size) {
            this(size, null);
        }

        public RoadBump(double size, String description) {
            this.size = size;
            this.description = description;
        }
    }

}
