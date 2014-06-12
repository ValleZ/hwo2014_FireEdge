package noobbot;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import noobbot.json_objects.*;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;

public final class Main {

    private static final int NO_ACCELL_DATA_COLLECTED = -1;
    private static final int ACCELL_DATA_COLLECTED = -2;
    private static final int DATA_POINTS_INC_STEP = 50;

    public static void main(String... args) {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String botName = args[2];
        String botKey = args[3];
        SendMsg joinCommand = null;
        if (args.length >= 5) {
            String extraParam = args[4];
            if (extraParam.startsWith("create:")) {
                String[] createParams = extraParam.split(":");
                if (createParams.length < 4 || createParams[3].length() == 0) {
                    joinCommand = new CreateRace(botName, botKey, createParams[1], Integer.parseInt(createParams[2]));
                } else {
                    joinCommand = new CreatePrivateRace(botName, botKey, createParams[1], Integer.parseInt(createParams[2]), createParams[3]);
                }
            } else if (extraParam.startsWith("join:")) {
                String[] joinParams = extraParam.split(":");
                if (joinParams.length == 4) {
                    joinCommand = new JoinPrivateRace(botName, botKey, joinParams[1], Integer.parseInt(joinParams[2]), joinParams[3]);
                }
            }
        }

        boolean production;
        if (joinCommand == null) {
            joinCommand = new Join(botName, botKey);
            production = true;
        } else {
            production = false;
        }
        Game game = new Game();
        System.gc();


        System.out.println("Connecting to " + host + ":" + port + " as " + botName + "/" + botKey);
        //IP testserver.helloworldopen.com/54.219.149.113
        final Socket socket;
        final PrintWriter writer;
        final BufferedReader reader;
        try {
            //todo SocketChannel?
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            System.out.println("IP " + socket.getInetAddress());
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "utf-8"));
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "utf-8"));
        } catch (Throwable e) {
            e.printStackTrace();
            System.out.println("cannot connect");
            return;
        }
        try {
            new Main(reader, writer).run(game, joinCommand, production);
            System.out.println("app exit");
        } catch (Throwable e) {
            e.printStackTrace();
            System.out.println("app error " + e);
        } finally {
            try {
                socket.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            game.stopFormulaFinder();
        }
//        new FormulaFinder(Debug.speeds, Debug.radiuses, Debug.angles, new FormulaFinder.Listener() {
//            @Override
//            public void onResultsFound(int pointsCount, double anglePredictorMinRAccell, double m, double xi, double w, double error) {
//
//            }
//        }).start();
    }

    private final Gson gson = new Gson();
    private final ArrayList<CarPosition> recycledCarPositions = new ArrayList<>(8);
    private final BufferedReader reader;
    private final PrintWriter writer;

    public Main(final BufferedReader reader, final PrintWriter writer) {
        this.reader = reader;
        this.writer = writer;
    }


    void run(Game game, SendMsg joinCommand, boolean production) throws IOException {

        writer.println(joinCommand.toJson());
        writer.flush();

        String line;
        int lastPieceIndex = -1;
        AngleState currentAngleState = new AngleState(game);
        AngleState debugAngleState = new AngleState(game);
        AngleState angleStateForPrediction = new AngleState(game);
        AngleState nextAngleStateCorrected = new AngleState(game);
        AngleState nextAngleState = new AngleState(game);
        boolean turboAvailable = false;
        TurboData turbo = null;
        boolean crashed = false;
        boolean hadCrash = false;
        int turboAvailableTicksLeft = 0;
        double throttle = 0;
        double desiredSpeed = 0;
        double minAngle = 0;
        double maxAngle = 0;
        double maxResponseTime = 0;

        double[] radiuses = new double[100];
        double[] speeds = new double[radiuses.length];
        double[] angles = new double[radiuses.length];
        int pos = -1;
        boolean sentFirstCalibration = false;
        int lastEmergencyFormulaFinderLaunchPointsCount = 15;
        boolean inGame = false;

        boolean tournamentEnded = false;
        boolean collisionDetected;
        int accelerationParametersState = NO_ACCELL_DATA_COLLECTED;
        double[] v = new double[4];
        double accelerationInfoThrottle = 1;
        Delay angleDerivativeCalculator = new Delay(3);
        Delay pieceRadiuses = new Delay(3);
        double prevVelocity = 0;
        double prevA = Double.NaN;
        double predictedAngle = Double.NaN;
        double nextAngle = Double.NaN;
        double nextSpeed = Double.NaN;
        boolean noMoreDataCollecting = false;
        double brakingAmount = 0;
        Game.CarInfo myCarInfo = null;
        int switchingLaneAtPieceIndex = -1;
        int switchingLaneTo = 0;
        int prevStartLaneIndex = 100;
        int prevEndLaneIndex = 100;
        int pieceIndex = 0;
        double r = 100;
        double inPieceDistance = 0;
        final Throttle throttleCmd = new Throttle(1);
        final Turbo turboCmd = new Turbo();
        final ArrayList<CarPosition> carPositions = new ArrayList<>(8);

        JsonReader jsonReader = new JsonReader(new HwoStreamReader(reader));
        jsonReader.setLenient(true);
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {

            long gameTick = 0;
            String msgType = null;

//            System.out.println(line);
            long start = System.nanoTime();
            long startcp = start - 1;
            long startParse = start - 1;
            long startSetCarPositions = start - 1;

            boolean carPositionsMessage = false;

            SendMsg commandToSend = null;
            jsonReader.beginObject();
            try {
                while (jsonReader.hasNext()) {
                    String name = jsonReader.nextName();
                    switch (name) {
                        case "msgType":
                            msgType = jsonReader.nextString();
                            break;
                        case "gameTick":
                            String gameTickStr = jsonReader.nextString();
                            try {
                                if (gameTickStr.indexOf('.') >= 0) {
                                    gameTick = Math.round(Double.parseDouble(gameTickStr));
                                    System.out.println("double gameTick???");
                                } else {
                                    gameTick = Long.parseLong(gameTickStr);
                                }
                            } catch (Exception e) {
                                System.out.println("unable to parse gameTick " + gameTickStr);
                            }
                            break;
                        case "data":
                            switch (msgType) {
                                case "carPositions":
                                    carPositionsMessage = true;
                                    startParse = System.nanoTime();
                                    parseCarPositionsArray(carPositions, jsonReader);
                                    startSetCarPositions = System.nanoTime();
                                    game.setCarPositions(carPositions);
                                    startcp = System.nanoTime();
                                    break;
                                case "join":
                                    System.out.println("Joined");
                                    jsonReader.skipValue();
                                    break;
                                case "gameInit": {
                                    tournamentEnded = false;
                                    GameInit gameInit = gson.fromJson(jsonReader, GameInit.class);
                                    System.out.println("Race id is " + gameInit.race.track.id);
                                    game.init(gameInit);
                                }
                                break;
                                case "gameEnd": {
                                    Results resultsObj = gson.fromJson(jsonReader, Results.class);
                                    Results.ResultRecord[] results = resultsObj.results;
                                    for (int i = 0; i < results.length; i++) {
                                        System.out.println(i + ". " + results[i].car);
                                    }
                                    System.out.println("game end. min angle " + minAngle + " max angle " + maxAngle + " braking " + brakingAmount + " maxResponseTime " + maxResponseTime);
                                    System.gc();
                                    inGame = false;
                                    pos = -1;
                                }
                                break;
                                case "finish": {
                                    CarId carId = gson.fromJson(jsonReader, CarId.class);
                                    if (game.isMyCar(carId)) {
                                        inGame = false;
                                        System.out.println("my car finished");
                                    } else {
                                        System.out.println("other car finished " + carId.name);
                                    }
                                }
                                break;
                                case "gameStart": {
                                    lastPieceIndex = -1;
                                    currentAngleState = new AngleState(game);
                                    turboAvailable = false;
                                    turboAvailableTicksLeft = 0;
                                    throttle = 0;
                                    pos = -1;
                                    System.out.println("gameStart!");
                                    commandToSend = new Ping();
                                    inGame = true;
                                    jsonReader.skipValue();
                                    game.clearDisqualifiedCarsList();
                                    game.clearCrashedCarsList();
                                }
                                break;
                                case "yourCar": {
                                    CarId myCar = gson.fromJson(jsonReader, CarId.class);
                                    game.setMyCarColor(myCar.color);
                                    System.out.println("my car " + myCar);
                                }
                                break;
                                case "lapFinished": {
                                    LapFinished lapInfo = gson.fromJson(jsonReader, LapFinished.class);
                                    if (lapInfo != null) {
                                        if (game.isMyCar(lapInfo.car)) {
                                            System.out.println("lap finished! " + lapInfo);
                                            game.onLapFinish(hadCrash);
                                            hadCrash = false;
                                        } else {
                                            System.out.println("other car lap finished! " + lapInfo);
                                        }
                                    }
                                }
                                break;
                                case "crash":
                                    if (!crashed) {
                                        CarId carId = gson.fromJson(jsonReader, CarId.class);
                                        game.setCrashedCar(carId.color, true);
                                        if (game.isMyCar(carId)) {
                                            System.out.println("My car crashed " + getDebugInfoFast(game.getMyCarInfo(), game, desiredSpeed, throttle, currentAngleState, 0, r) + " tick " + gameTick);
                                            crashed = true;
                                            hadCrash = true;
                                            if (!noMoreDataCollecting && game.angleState == Game.ANGLE_STATE_NEED_ALL_PARAMS) {
                                                System.out.println("params collected " + pos + " calibration points so far");
                                                if (pos > lastEmergencyFormulaFinderLaunchPointsCount) {
                                                    lastEmergencyFormulaFinderLaunchPointsCount = pos;
                                                    game.calcParameters(false, speeds, radiuses, angles, pos - 1);
                                                }
                                                pos = -1;
                                                radiuses = new double[100];
                                                speeds = new double[radiuses.length];
                                                angles = new double[radiuses.length];
                                            } else {
                                                System.out.println("calibration actions not required noMoreDataCollecting " + noMoreDataCollecting + " angleState " + game.angleState);
                                            }
                                            game.onCrash();
                                            System.gc();
//                            System.exit(1);
                                        } else {
                                            System.out.println("Anther car crashed " + carId);
                                        }
                                    } else {
                                        System.out.println("Anther car crashed");
                                        jsonReader.skipValue();
                                    }
                                    break;
                                case "error":
                                    System.out.println("error");
                                    jsonReader.skipValue();
                                    break;
                                case "dnf": {
                                    DnfEvent dnfEvent = gson.fromJson(jsonReader, DnfEvent.class);
                                    System.out.println("disqualified " + dnfEvent);
                                    game.setQisqualified(dnfEvent.car.color);
                                }
                                break;
                                case "turboAvailable": {
                                    if (!crashed) {
                                        turbo = gson.fromJson(jsonReader, TurboData.class);
                                        System.out.println("have got turbo! " + turbo);
                                        turboAvailable = true;
                                        turboAvailableTicksLeft = 2;
                                    } else {
                                        jsonReader.skipValue();
                                    }
                                }
                                break;
                                case "turboStart": {
                                    CarId carId = gson.fromJson(jsonReader, CarId.class);
                                    if (game.isMyCar(carId)) {
                                        System.out.println("turbo started!");
                                        double turboFactor = 3;
                                        int turboDurationTicks = 30;
                                        if (turbo != null) {
                                            turboFactor = turbo.turboFactor;
                                            turboDurationTicks = (int) turbo.turboDurationTicks;
                                        }
                                        game.setTurboFactor(turboFactor, turboDurationTicks);
                                    }
                                }
                                break;
                                case "turboEnd": {
                                    CarId carId = gson.fromJson(jsonReader, CarId.class);
                                    if (game.isMyCar(carId)) {
                                        System.out.println("turbo end! " + gameTick);
                                        game.setTurboFactor(1, 0);
                                    }
                                }
                                break;
                                case "spawn": {
                                    if (crashed) {
                                        CarId carId = gson.fromJson(jsonReader, CarId.class);
                                        game.setCrashedCar(carId.color, false);
                                        if (game.isMyCar(carId)) {
                                            System.out.println("my car spawned");
                                            crashed = false;
                                            if (pos >= 0) {
                                                pos = -1;
                                            }
                                        } else {
                                            System.out.println("another car spawned " + carId);
                                        }
                                    } else {
                                        jsonReader.skipValue();
                                    }
                                }
                                break;
                                case "tournamentEnd":
                                    tournamentEnded = true;
                                    jsonReader.skipValue();
                                    break;
                                case "createRace":
                                    System.out.println("createRace echo");
                                    jsonReader.skipValue();
                                    break;
                                case "joinRace":
                                    System.out.println("joinRace echo");
                                    jsonReader.skipValue();
                                    break;
                                default:
                                    jsonReader.skipValue();
                                    break;
                            }
                            break;
                        default:
                            jsonReader.skipValue();
                            break;
                    }
                }
            } catch (Throwable th) {
                System.out.println("failed to process command");
                th.printStackTrace();
            }
            jsonReader.endObject();


            if (carPositionsMessage) {
                try {
                    myCarInfo = game.getMyCarInfo();
                    if (myCarInfo != null) {
                        double oldThrottle = throttle;

                        if (Math.abs(nextSpeed - myCarInfo.velocity) < 0.0001 && game.anglePredictorCurrentError < 0.0001) {
                            if (
                                    (game.angleState == Game.ANGLE_STATE_PERFECT_PARAMS_FOUND && (Math.abs(nextAngle - myCarInfo.angle) > 0.00001)) ||
                                            (game.angleState == Game.ANGLE_STATE_NEED_ALL_PARAMS && (Math.abs(nextAngle - myCarInfo.angle) > 0.005))

                                    ) {
                                GameInit.Race.Track.Piece prevTickPiece = game.track.pieces[pieceIndex];
                                if (prevTickPiece.length <= 0) {
                                    double distanceFromCenterStart = game.track.lanes[prevStartLaneIndex].distanceFromCenter;
                                    double distanceFromCenterEnd = game.track.lanes[prevEndLaneIndex].distanceFromCenter;
                                    double radiusStart = prevTickPiece.getRadius(distanceFromCenterStart);
                                    double radiusEnd = prevTickPiece.getRadius(distanceFromCenterEnd);
                                    double pieceOffsRelative = inPieceDistance / game.getLaneLength(prevTickPiece, distanceFromCenterStart, distanceFromCenterEnd);
                                    double sign = Math.signum(prevTickPiece.angle);
                                    double realR = (game.anglePredictorA * prevVelocity * prevVelocity / (sign * (myCarInfo.angle - nextAngle) + game.anglePredictorA * prevVelocity * prevVelocity / Math.sqrt(Math.abs(r))));
                                    realR *= realR * sign;

                                    nextAngleStateCorrected.copy(currentAngleState);
                                    nextAngleStateCorrected.update(prevVelocity, realR);
                                    double nextAngleCorrected = nextAngleStateCorrected.angle;
                                    double correctedErr = myCarInfo.angle - nextAngleCorrected;
                                    if (Math.abs(correctedErr) < 0.00001) {
                                        game.learnWeirdBendedSwitchCurve(realR, prevTickPiece.angle, radiusStart, radiusEnd, pieceOffsRelative);
                                    }

                                    System.out.println("WRONG ANGLE PREDICTION! p " + pieceIndex + " angle " + prevTickPiece.angle + " radiusStart " + radiusStart + " radiusEnd " + radiusEnd + " => " + (myCarInfo.angle - nextAngle) +
                                            ". pieceOffsRelative " + pieceOffsRelative + " real R " + realR + " was " + r + " correctedErr " + correctedErr);
                                } else {
                                    double pieceOffsRelative = inPieceDistance / prevTickPiece.length;
                                    System.out.println("WRONG ANGLE PREDICTION! length " + prevTickPiece.length + " => " + (myCarInfo.angle - nextAngle) + ". pieceOffsRelative " + pieceOffsRelative);
                                }
                            }
//                        else {
//                            if (prevStartLaneIndex != prevEndLaneIndex) {
//                                GameInit.Race.Track.Piece prevTickPiece = game.track.pieces[pieceIndex];
//                                if (prevTickPiece.length <= 0) {
//                                    double distanceFromCenterStart = game.track.lanes[prevStartLaneIndex].distanceFromCenter;
//                                    double distanceFromCenterEnd = game.track.lanes[prevEndLaneIndex].distanceFromCenter;
//                                    double radiusStart = prevTickPiece.getRadius(distanceFromCenterStart);
//                                    double radiusEnd = prevTickPiece.getRadius(distanceFromCenterEnd);
//                                    double pieceOffsRelative = inPieceDistance / game.getLaneLength(prevTickPiece, distanceFromCenterStart, distanceFromCenterEnd);
//
//                                    double realR = (AngleState.anglePredictorA * prevVelocity * prevVelocity / ((myCarInfo.angle - nextAngle) + Math.signum(prevTickPiece.angle) * AngleState.anglePredictorA * prevVelocity * prevVelocity / Math.sqrt(Math.abs(r))));
//                                    realR *= realR;
//
//                                    AngleState nextangleStateCorrected = new AngleState(currentAngleState);
//                                    nextangleStateCorrected.update(prevVelocity, realR);
//                                    double nextAngleCorrected = nextangleStateCorrected.degrees();
//                                    double correctedErr = myCarInfo.angle - nextAngleCorrected;
//
//
//                                    System.out.println("CORRECT ANGLE PREDICTION! p " + pieceIndex + " angle " + prevTickPiece.angle + " radiusStart " + radiusStart + " radiusEnd " + radiusEnd + " => " + (myCarInfo.angle - nextAngle) +
//                                            ". pieceOffsRelative " + pieceOffsRelative + " real R " + realR + " was " + Math.abs(r) + " correctedErr " + correctedErr);
//                                } else {
//                                    double pieceOffsRelative = inPieceDistance / prevTickPiece.length;
//                                    System.out.println("CORRECT ANGLE PREDICTION! length " + prevTickPiece.length + " => " + (myCarInfo.angle - nextAngle) + ". pieceOffsRelative " + pieceOffsRelative);
//                                }
//                            }
//                        }
                        }

                        r = myCarInfo.getR();
                        pieceIndex = myCarInfo.pieceIndex;
                        inPieceDistance = myCarInfo.inPieceDistance;
                        angleDerivativeCalculator.push(myCarInfo.angle);
                        double expectedAngleSecondDerivativeBeforeCutoff = -game.anglePredictorC * prevVelocity * angleDerivativeCalculator.getPrev() - game.anglePredictorB * currentAngleState.angleDer;
                        currentAngleState.angleDer = angleDerivativeCalculator.getDerivative();
                        currentAngleState.angle = myCarInfo.angle;

                        if (pieceIndex != lastPieceIndex && game.track.pieces[game.getNextPieceIndex(pieceIndex)].haveSwitch && inGame && accelerationParametersState == ACCELL_DATA_COLLECTED &&
                                ((game.angleState != Game.ANGLE_STATE_NOT_INITED && game.anglePredictorCurrentError < 0.0001) || noMoreDataCollecting)) {
                            if (myCarInfo.startLaneIndex == myCarInfo.endLaneIndex) {
                                switchingLaneAtPieceIndex = -1;
                            }
                            try {
                                LaneSwitch.Options laneShift = game.whereWeShouldTurn(currentAngleState, oldThrottle);
                                if (laneShift != null) {
                                    commandToSend = new LaneSwitch(laneShift);

                                    switchingLaneAtPieceIndex = game.getNextPieceIndex(pieceIndex);
                                    switchingLaneTo = myCarInfo.endLaneIndex + (laneShift == LaneSwitch.Options.LEFT ? -1 : 1);

                                    if (!noMoreDataCollecting) {
                                        noMoreDataCollecting = true;
                                        pos = -1;
                                        System.out.println("noMoreDataCollecting! lane switch!");
                                    }
                                }
                                lastPieceIndex = pieceIndex;
                            } catch (Exception e) {
                                System.out.println("ERROR in switcher " + e);
                            }
                        }
                        if (myCarInfo.prevCommandTick != gameTick - 1 && myCarInfo.prevCommandTick > 0 && !crashed && inGame) {
                            System.out.println("LATENCY! " + (gameTick - myCarInfo.prevCommandTick) + " received " + gameTick + " when server processed " + myCarInfo.prevCommandTick +
                                    " dist " + myCarInfo.totalDist + " angle " + myCarInfo.angle + " r " + r + " last throttle " + throttle);
                            if (!noMoreDataCollecting && game.angleState == Game.ANGLE_STATE_NEED_ALL_PARAMS) {
                                System.out.println("params collected " + pos + " calibration points so far (latency)");
                                if (pos > lastEmergencyFormulaFinderLaunchPointsCount) {
                                    lastEmergencyFormulaFinderLaunchPointsCount = pos;
                                    game.calcParameters(false, speeds, radiuses, angles, pos - 2);
                                }
                                pos = -1;
                                radiuses = new double[100];
                                speeds = new double[radiuses.length];
                                angles = new double[radiuses.length];
                            } else {
                                System.out.println("calibration actions not required noMoreDataCollecting " + noMoreDataCollecting + " angleState " + game.angleState);
                            }

                        }
//                    System.out.printf("real angle %10.6f emul angle %10.6f der real? %10.6f der emul %10.6f\n", myCarInfo.angle, debugAngleState.angle, currentAngleState.angleDer, debugAngleState.angleDer);
                        pieceRadiuses.push(r);
                        debugAngleState.update(myCarInfo.velocity, r);

                        if (game.angleState == Game.ANGLE_STATE_NOT_INITED && r < 1000000000 && pieceRadiuses.areAllSame()) {
                            double angleSecondDerivative = angleDerivativeCalculator.secondDerivative();
                            if (Math.abs(angleSecondDerivative - expectedAngleSecondDerivativeBeforeCutoff) > 0.01 && !angleDerivativeCalculator.haveZeroes()) {
                                double a = (angleSecondDerivative - expectedAngleSecondDerivativeBeforeCutoff + game.anglePredictorMinRAccell * prevVelocity * Math.signum(r)) * Math.sqrt(Math.abs(r)) / (Math.signum(r) * prevVelocity * prevVelocity);
                                if (prevA != prevA) {
                                    System.out.println("maybe anglePredictorA is " + a);
                                    prevA = a;
                                    game.anglePredictorA = a;
                                    angleStateForPrediction.copy(currentAngleState);
                                    angleStateForPrediction.update(myCarInfo.velocity, r);
                                    predictedAngle = angleStateForPrediction.angle;
                                } else {
                                    System.out.println("angle " + myCarInfo.angle + " predicted " + predictedAngle + " diff " + (predictedAngle - myCarInfo.angle));
                                    if (Math.abs(prevA - a) < 1e-10 && Math.abs(predictedAngle - myCarInfo.angle) < 1e-10) {
                                        System.out.println("anglePredictorA is " + a + " for sure, params are correct");
                                        game.anglePredictorA = a;
                                        game.anglePredictorCurrentError = 0.0001;
                                        game.angleState = Game.ANGLE_STATE_PERFECT_PARAMS_FOUND;
                                    } else {
                                        System.out.println("diff anglePredictorA is too big " + (prevA - a) + " or predicted err is too big " + (predictedAngle - myCarInfo.angle) + " set state - need all params");
                                        game.angleState = Game.ANGLE_STATE_NEED_ALL_PARAMS;
                                    }
                                    prevA = a;
                                }
                            } else if (Math.abs(angleSecondDerivative) > 0.001) {
                                System.out.println("angleSecondDerivative " + angleSecondDerivative + " expectedAngleSecondDerivativeBeforeCutoff " + expectedAngleSecondDerivativeBeforeCutoff);
                            }
                        }

                        collisionDetected = false;
                        double carDist = inGame && !crashed ? game.lookAroundGetDistanceToANearByVehicle() : Double.MAX_VALUE;
                        if (inGame && !crashed && accelerationParametersState == ACCELL_DATA_COLLECTED && Math.abs(nextSpeed - myCarInfo.velocity) > 0.0001 && myCarInfo.velocity > 0) {

                            System.out.println("COLLISION??? expected " + nextSpeed + " fact " + myCarInfo.velocity + " diff " + (myCarInfo.velocity - nextSpeed) + " my lane " + myCarInfo.startLaneIndex + "-" + myCarInfo.endLaneIndex +
                                    " dist to '" + game.nearestCarName + "' is " + carDist + " their car laneend " + game.nearestCarEndLineIndex);
                            collisionDetected = true;
                            if (!noMoreDataCollecting && (game.angleState != Game.ANGLE_STATE_PERFECT_PARAMS_FOUND)) {
                                if (pos > lastEmergencyFormulaFinderLaunchPointsCount) {
                                    lastEmergencyFormulaFinderLaunchPointsCount = pos;
                                    System.out.println("params collision? collected " + pos + " calibration points so far");
                                    game.calcParameters(false, speeds, radiuses, angles, pos - 1);
                                }
                                pos = -1;
                                if (radiuses.length > 100) {
                                    radiuses = new double[100];
                                    speeds = new double[radiuses.length];
                                    angles = new double[radiuses.length];
                                }
                            } else {
                                System.out.println("calibration actions not required noMoreDataCollecting " + noMoreDataCollecting + " angleState " + game.angleState);
                            }


                            if (myCarInfo.startLaneIndex == myCarInfo.endLaneIndex && prevStartLaneIndex != prevEndLaneIndex && prevEndLaneIndex < 100) {
                                if (carDist > game.getMyCarLength() * 2) {
                                    int prevPieceIndex = game.getPrevPieceIndex(myCarInfo.pieceIndex);
                                    GameInit.Race.Track.Piece prevPiece = game.track.pieces[prevPieceIndex];
                                    double diff = myCarInfo.velocity - nextSpeed;
                                    if (prevPiece.length <= 0) {
                                        double radiusStart = prevPiece.getRadius(game.track.lanes[prevStartLaneIndex].distanceFromCenter);
                                        double radiusEnd = prevPiece.getRadius(game.track.lanes[prevEndLaneIndex].distanceFromCenter);
                                        System.out.println("switch bump! angle " + prevPiece.angle + " radiusStart " + radiusStart + " radiusEnd " + radiusEnd + " => " + (diff));
                                        game.learnRoadBumpForCurve(diff, Math.abs(prevPiece.angle), radiusStart, radiusEnd);
                                    } else {
                                        System.out.println("straight switch bump! length " + prevPiece.length + " => " + (myCarInfo.velocity - nextSpeed));
                                        game.learnRoadBumpForStraight(diff, prevPiece.length);
                                    }
                                } else {
                                    System.out.println("it is not a switch bump because carDist " + carDist);
                                }
                            }
                        }

                        prevVelocity = myCarInfo.velocity;
                        prevStartLaneIndex = myCarInfo.startLaneIndex;
                        prevEndLaneIndex = myCarInfo.endLaneIndex;
                        if (myCarInfo.angle < minAngle) {
                            minAngle = myCarInfo.angle;
                        }
                        if (myCarInfo.angle > maxAngle) {
                            maxAngle = myCarInfo.angle;
                        }

                        long startDSpeed = System.nanoTime();
                        desiredSpeed = game.getDesiredSpeed(myCarInfo, currentAngleState, switchingLaneAtPieceIndex, switchingLaneTo,
                                game.nearestCarRearDist, game.nearestCarRearDistDeltaVelocity,
                                game.nearestCarFrontDist, game.nearestCarFrontDistDeltaVelocity, -1);
                        long dSpeedTime = (System.nanoTime() - startDSpeed) / 1000;
                        long startLastCarPos = System.nanoTime();
                        if (dSpeedTime > 2000) {
                            System.out.println("getDesiredSpeed part took " + dSpeedTime + " mks, desiredSpeedDecreasesCount " + game.desiredSpeedDecreasesCount + " desiredSpeedIncreasesCount " + game.desiredSpeedIncreasesCount + " tick " + gameTick);
                        }

                        throttle = game.getThrottle(myCarInfo.velocity, desiredSpeed);

                        if (throttle < oldThrottle) {
                            brakingAmount += (oldThrottle - throttle) * (oldThrottle - throttle);
                        }
//                    throttle = Game.FAKE_THROTTLE;
//                    throttle *= random.nextdouble();
//                    throttle = (double) Math.min(throttle * 1.2, 1);
                        boolean noActiveTurbo = game.getTurboFactor() < 1.01;
                        if (inGame && accelerationParametersState == NO_ACCELL_DATA_COLLECTED && myCarInfo.velocity > 0 && noActiveTurbo) {//TODO check for nearby cars
                            //we need to collect 4 speed measurements for a constant throttle
                            accelerationInfoThrottle = throttle;
                            accelerationParametersState = 0;
                        }
                        if (accelerationParametersState >= 0) {
                            //forced car param calculation
                            v[accelerationParametersState] = myCarInfo.velocity;
                            throttle = accelerationInfoThrottle;
                            accelerationParametersState++;
                            if (accelerationParametersState == v.length) {
                                accelerationParametersState = ACCELL_DATA_COLLECTED;
                                game.drag = (v[0] - (v[1] - v[0])) / (v[0] * v[0]) * accelerationInfoThrottle;
                                game.mass = 1 / (Math.log((v[2] - accelerationInfoThrottle / game.drag) / (v[1] - accelerationInfoThrottle / game.drag)) / (-game.drag));
                                System.out.println("K = " + game.drag + " mass = " + game.mass);
                            }
                        }

                        if (noActiveTurbo) {
                            turboAvailableTicksLeft--;
                        }
                        if (inGame) {
                            game.updatePowerDeficitForPiece(myCarInfo.pieceIndex);
                            if (commandToSend == null) {
                                if (turboAvailable && turboAvailableTicksLeft <= 0 &&
                                        ((game.isItAGoodPieceForTurbo(myCarInfo.lap, myCarInfo.pieceIndex) && game.getPowerDeficit() >= 100) || game.getPowerDeficit() >= 300)
                                        && accelerationParametersState == ACCELL_DATA_COLLECTED &&
                                        Math.abs(myCarInfo.angle) < game.getCrashAngle() - Game.EXTRA_SAFETY_ANGLE_FOR_TURBO) {
                                    System.out.println("Request turbo, potential " + game.getPowerDeficit() + " dist " + myCarInfo.totalDist + " tick " + gameTick + " piece " + myCarInfo.pieceIndex +
                                            " piece pd " + game.powerDeficitsByPiece[myCarInfo.pieceIndex] + " good piece " + game.isItAGoodPieceForTurbo(myCarInfo.lap, myCarInfo.pieceIndex));
                                    commandToSend = turboCmd;
                                    turboAvailable = false;
                                    nextSpeed = game.getNextSpeed(myCarInfo.velocity, oldThrottle);
                                } else {
                                    throttleCmd.value = throttle;
                                    commandToSend = throttleCmd;
                                    nextSpeed = game.getNextSpeed(myCarInfo.velocity, throttle * game.getTurboFactor());
//                                System.out.println("speed predicted with TF " + game.getTurboFactor() + " turbo ticks left " + game.getTurboTicksLeft() + " sp " + nextSpeed + " tick " + gameTick);
                                }
                            } else {
                                nextSpeed = game.getNextSpeed(myCarInfo.velocity, oldThrottle * game.getTurboFactor());
                            }
                        }

                        if (pos < 0 && myCarInfo.velocity > 0.1 && !crashed && !noMoreDataCollecting && !collisionDetected && Math.abs(game.nearestCarFrontDist) > 2 && Math.abs(game.nearestCarRearDist) > 2) {
                            if (Math.abs(currentAngleState.angleDer) < 0.01) {
                                System.out.println("start params points collecting");
                                pos = 0;//start data collection
                            }
                        }
                        if (pos >= 0 && pos < radiuses.length && inGame) {
                            radiuses[pos] = r;
                            speeds[pos] = myCarInfo.velocity;
                            angles[pos] = myCarInfo.angle;
                            System.out.println("collected " + pos + " points");
                            pos++;
                            if (pos == radiuses.length || (!sentFirstCalibration && Math.abs(myCarInfo.angle) > 15 && pos > 30)) {
//                            if (raccs.length == 700) {
//                                for (int j = 0; j < raccs.length - 1; j++) {
//                                    System.out.printf("radius %10.1f, speed %10.7f, angle %8.4f,\n", radiuses[j], speeds[j], angles[j + 1]);
//                                }
//                            }
                                boolean lastSample = angles.length > 600 || (game.anglePredictorCurrentError < 0.00000001 && game.anglePredictorPointsCount > 100);
                                sentFirstCalibration = true;
                                System.out.println("calc params for " + (pos - 1) + " points");
                                game.calcParameters(lastSample, speeds, radiuses, angles, pos - 1);
                                if (!lastSample) {

                                    double[] radiusesNew = new double[radiuses.length + DATA_POINTS_INC_STEP];
                                    double[] speedsNew = new double[speeds.length + DATA_POINTS_INC_STEP];
                                    double[] anglesNew = new double[angles.length + DATA_POINTS_INC_STEP];
                                    System.arraycopy(radiuses, 0, radiusesNew, 0, angles.length);
                                    System.arraycopy(speeds, 0, speedsNew, 0, angles.length);
                                    System.arraycopy(angles, 0, anglesNew, 0, angles.length);
                                    radiuses = radiusesNew;
                                    speeds = speedsNew;
                                    angles = anglesNew;

                                } else {
                                    System.out.println("params noMoreDataCollecting! points collected: " + angles.length);
                                    noMoreDataCollecting = true;
                                }
                            }
                        }

                        nextAngleState.copy(currentAngleState);
                        nextAngleState.update(myCarInfo.velocity, r);
                        nextAngle = nextAngleState.angle;
                        if (inGame && !crashed
//                                && Math.abs(myCarInfo.angle) > game.getCrashAngle()
                                ) {

                            System.out.println(getDebugInfoFast(myCarInfo, game, desiredSpeed, throttle, currentAngleState, nextAngle, r) + " tick " + gameTick);
                        }
                        long lastPartTime = (System.nanoTime() - startLastCarPos) / 1000;
                        if (lastPartTime > 4000) {
                            System.out.println("last CarPos part took " + lastPartTime + " mks " + " tick " + gameTick);
                        }
                    } else {
                        System.out.println("cap positions processing failed because no my car info found!");
                        commandToSend = new Throttle(1);
                    }
                } catch (Throwable th) {
                    th.printStackTrace();
                    System.out.println("failed to process car positions");
                }
            }


            long timeMks = (System.nanoTime() - start) / 1000;
            if (timeMks > 8000) {
                System.out.println((timeMks > 15000 ? "!!!" : "") + " calc time took " + timeMks + " mks cmd " + msgType + " cp without parse " + (System.nanoTime() - startcp) / 1000 + " tick " + gameTick +
                        " dist " + (myCarInfo == null ? "?" : (myCarInfo.totalDist + "")));
                System.out.println(" ...calc time ctnd: parse data " + (startSetCarPositions - startParse) / 1000 + " mks apply " + (startcp - startSetCarPositions) / 1000 + " mks parse total " + (startSetCarPositions - start) / 1000);
            }
            if (gameTick > 0) {
                if (timeMks > maxResponseTime) {
                    maxResponseTime = timeMks;
                }
            }
            if (commandToSend != null) {
                String jsonResponse = commandToSend.toJson();
//                if (production) {
//                    System.out.println("send " + jsonResponse);
//                }
                writer.println(jsonResponse);
                writer.flush();
            } else {
//                if (production) {
//                    System.out.println("command '" + line + "' ignored, no response sent");
//                }
            }
            if (tournamentEnded) {
                System.out.println("tournamentEnd, exit!");
//                game.printRoadBumps();
                return;
            }
        }
        jsonReader.endArray();
        System.out.println("end of stream");
    }

    private void parseCarPositionsArray(ArrayList<CarPosition> carPositions, JsonReader jsonReader) throws IOException {
        recycledCarPositions.addAll(carPositions);
        carPositions.clear();
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
            CarPosition cp = !recycledCarPositions.isEmpty() ? recycledCarPositions.remove(recycledCarPositions.size() - 1) : new CarPosition();
            jsonReader.beginObject();
            while (jsonReader.hasNext()) {
                switch (jsonReader.nextName()) {
                    case "id":
                        jsonReader.beginObject();
                        while (jsonReader.hasNext()) {
                            switch (jsonReader.nextName()) {
                                case "name":
                                    cp.name = jsonReader.nextString();
                                    break;
                                case "color":
                                    cp.color = jsonReader.nextString();
                                    break;
                                default:
                                    jsonReader.skipValue();
                                    break;
                            }
                        }
                        jsonReader.endObject();
                        break;
                    case "angle":
                        cp.angle = jsonReader.nextDouble();
                        break;
                    case "piecePosition":
                        jsonReader.beginObject();
                        while (jsonReader.hasNext()) {
                            switch (jsonReader.nextName()) {
                                case "pieceIndex":
                                    cp.pieceIndex = jsonReader.nextInt();
                                    break;
                                case "lap":
                                    cp.lap = jsonReader.nextInt();
                                    break;
                                case "inPieceDistance":
                                    cp.inPieceDistance = jsonReader.nextDouble();
                                    break;
                                case "lane":
                                    jsonReader.beginObject();
                                    while (jsonReader.hasNext()) {
                                        switch (jsonReader.nextName()) {
                                            case "startLaneIndex":
                                                cp.startLaneIndex = jsonReader.nextInt();
                                                break;
                                            case "endLaneIndex":
                                                cp.endLaneIndex = jsonReader.nextInt();
                                                break;
                                            default:
                                                jsonReader.skipValue();
                                                break;
                                        }
                                    }
                                    jsonReader.endObject();
                                    break;
                                default:
                                    jsonReader.skipValue();
                                    break;
                            }
                        }
                        jsonReader.endObject();
                        break;
                    case "prevCommandTick":
                        cp.prevCommandTick = jsonReader.nextInt();
                        break;
                }
            }
            jsonReader.endObject();
            carPositions.add(cp);
        }
        jsonReader.endArray();
    }

    private String getDebugInfo(Game.CarInfo myCarInfo, Game game, double desiredSpeed, double throttle, AngleState as, double nextAngle) {
        GameInit.Race.Track.Piece piece = game.track.pieces[myCarInfo.pieceIndex];
        double racc = myCarInfo.getRAcceleration();
        double r = myCarInfo.getR();
        if (r > 100000) {
            r = 0;
        }
        double rst = (piece.radius + (piece.angle > 0 ? -game.track.lanes[myCarInfo.startLaneIndex].distanceFromCenter : game.track.lanes[myCarInfo.startLaneIndex].distanceFromCenter));
        double re = (piece.radius + (piece.angle > 0 ? -game.track.lanes[myCarInfo.endLaneIndex].distanceFromCenter : game.track.lanes[myCarInfo.endLaneIndex].distanceFromCenter));
        return String.format("dist %9.4f velocity %9.6f of %6.2f acc %8.5f nextSpeed %9.6f RAcc %5.2f angle %8.4f angle' %7.3f nextAngle %8.4f powdef %5.0f " +
                        "THROTTLE %5.2f " +
                        "lap %2d piece %2d %6.2f of %6.2f lane%2d%2d pieceR %7.0f rst %4.0f re %4.0f "
                        + "rearDist " + game.nearestCarRearDist + " vel " + game.nearestCarRearDistDeltaVelocity + " ttc " + game.nearestCarRearTimeToCollision + " to " + game.nearestCarRearName
                        + " frontDist " + game.nearestCarFrontDist + " vel " + game.nearestCarFrontDistDeltaVelocity + " ttc " + game.nearestCarFrontTimeToCollision + " to " + game.nearestCarFrontName +
                        (Math.abs(myCarInfo.angle) > game.getCrashAngle() ? " CRASH ANGLE! +" + (Math.abs(myCarInfo.angle) - game.getCrashAngle()) : ""),

                myCarInfo.totalDist, myCarInfo.velocity, desiredSpeed, myCarInfo.acceleration,

                game.getNextSpeed(myCarInfo.velocity, throttle * game.getTurboFactor()),


                racc, myCarInfo.angle, as.angleDer,
                nextAngle,
                game.getPowerDeficit(),
                throttle,
                myCarInfo.lap, myCarInfo.pieceIndex, myCarInfo.inPieceDistance,
                game.getLaneLength(piece, game.track.lanes[myCarInfo.endLaneIndex].distanceFromCenter, game.track.lanes[myCarInfo.startLaneIndex].distanceFromCenter),
                myCarInfo.startLaneIndex, myCarInfo.endLaneIndex,
                r, rst > 10000 ? 0 : rst, re > 10000 ? 0 : re
        );
    }

    private String getDebugInfoFast(Game.CarInfo myCarInfo, Game game, double desiredSpeed, double throttle, AngleState as, double nextAngle, double r) {
        GameInit.Race.Track.Piece piece = game.track.pieces[myCarInfo.pieceIndex];
        if (r > 100000) {
            r = 0;
        }
        double rst = (piece.radius + (piece.angle > 0 ? -game.track.lanes[myCarInfo.startLaneIndex].distanceFromCenter : game.track.lanes[myCarInfo.startLaneIndex].distanceFromCenter));
        double re = (piece.radius + (piece.angle > 0 ? -game.track.lanes[myCarInfo.endLaneIndex].distanceFromCenter : game.track.lanes[myCarInfo.endLaneIndex].distanceFromCenter));
        return "dist " + myCarInfo.totalDist + " velocity " + myCarInfo.velocity + " of " + desiredSpeed + " acc " + myCarInfo.acceleration +
                " nextSpeed " + game.getNextSpeed(myCarInfo.velocity, throttle * game.getTurboFactor()) + " angle " + myCarInfo.angle +
                " angle' " + as.angleDer + " nextAngle " + nextAngle + " powdef " + game.getPowerDeficit() +
                " THROTTLE " + throttle +
                "lap " + myCarInfo.lap + " piece " + myCarInfo.pieceIndex + " " + myCarInfo.inPieceDistance +
                " lane " + myCarInfo.startLaneIndex + " " + myCarInfo.endLaneIndex + " pieceR " + r + " rst " + (rst > 10000 ? 0 : rst) + " re " + (re > 10000 ? 0 : re) +
                " rearDist " + game.nearestCarRearDist + " vel " + game.nearestCarRearDistDeltaVelocity + " ttc " + game.nearestCarRearTimeToCollision + " to " + game.nearestCarRearName
                + " frontDist " + game.nearestCarFrontDist + " vel " + game.nearestCarFrontDistDeltaVelocity + " ttc " + game.nearestCarFrontTimeToCollision + " to " + game.nearestCarFrontName +
                (Math.abs(myCarInfo.angle) > game.getCrashAngle() ? " CRASH ANGLE! +" + (Math.abs(myCarInfo.angle) - game.getCrashAngle()) : ""
                );
    }
}

