package noobbot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

public final class FormulaFinder {
    private final double[] speeds;
    private final double[] radiuses;
    private final double[] angles;
    private final Listener listener;
    private Thread thread;
    private final ArrayList<CalculatedAngleState> recycledItems = new ArrayList<>();
    private CalculatedAngleState initial = null;
    private volatile boolean stopped;

    public void setInitialParams(double minRAccell, double A, double B, double C) {
        CalculatedAngleState as = getNew(recycledItems);
        as.minRAccell = minRAccell;
        as.A = A;
        as.B = B;
        as.C = C;
        initial = as;
    }

    public static interface Listener {
        public void onResultsFound(int pointsCount, double minRAccell, double A, double B, double C, double error);
    }

    public FormulaFinder(double[] speeds, double[] radiuses, double[] angles, Listener listener) {
        this.speeds = speeds;
        this.radiuses = radiuses;
        this.angles = angles;
        this.listener = listener;
    }

    public void start() {
        if (thread == null) {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    findFormula();
                }
            });
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }
    }

    public void stop() {
        if (thread != null) {
            stopped = true;
            thread = null;
        }
    }


    final Random random = new Random();

    private void findFormula() {
        double bestReportedSqError = 100000;
        CalculatedAngleState gridAngleState = getNew(recycledItems);
        if (initial != null) {
            gridAngleState.B = initial.B;
            gridAngleState.C = initial.C;
            double minErr = Double.MAX_VALUE;
            double bestMinRAcc = 0;
            double bestA = 0;
            for (double gridMinRAcc = 0.2; gridMinRAcc < 0.51; gridMinRAcc += 0.01) {
                gridAngleState.minRAccell = gridMinRAcc;
                for (double gridA = 0.4; gridA < 0.8; gridA += 0.01) {
                    gridAngleState.A = gridA;
                    double maxSqrError = 0;
                    gridAngleState.angle = angles[1];
                    gridAngleState.angleDer = angles[1] - angles[0];;
                    for (int j = 2; j < speeds.length - 1; j++) {
                        gridAngleState.update(speeds[j], radiuses[j]);
                        double errForPoint = gridAngleState.angle - angles[j + 1];
                        errForPoint *= errForPoint;
                        if (errForPoint > maxSqrError) {
                            maxSqrError = errForPoint;
                        }
                    }
                    if (maxSqrError < minErr) {
                        minErr = maxSqrError;
                        bestMinRAcc = gridMinRAcc;
                        bestA = gridA;
                    }
                }
            }
            gridAngleState.A = bestA;
            gridAngleState.minRAccell = bestMinRAcc;
            gridAngleState.sqrErr = minErr;
            bestReportedSqError = minErr;
            publishResults(gridAngleState);
        }


        int populationSize = 35;
        int countSurvivors = 1;
        int selectionCount = populationSize / 2;
        CalculatedAngleState[] population = new CalculatedAngleState[populationSize];
        for (int i = 0; i < populationSize; i++) {
            population[i] = getRandom(recycledItems);
        }
        if (initial != null) {
            population[1] = population[0] = initial;
            for (int i = 0; i < populationSize / 2; i++) {
                population[i].B = initial.B;
                population[i].C = initial.C;
            }
        }
        population[3] = population[2] = gridAngleState;
        double maxInputAngle = 0;
        for (double angle : angles) {
            if (angle > maxInputAngle) {
                maxInputAngle = angle;
            }
        }
        CalculatedAngleState[] newPopulation = new CalculatedAngleState[populationSize];
        double mutateK = 0.1;
        for (int generation = 0; generation < 10000; generation++) {
            Thread.yield();
            for (CalculatedAngleState as : population) {
//                Thread.yield();
                as.angleDer = angles[1] - angles[0];
                as.angle = angles[1];
                double maxSqrError = 0;
                for (int j = 2; j < angles.length - 1; j++) {
                    as.update(speeds[j], radiuses[j]);
                    double errForPoint = as.angle - angles[j + 1];
//                    boolean overflow = (errForPoint < 0 && as.angle > 45) || (errForPoint > 0 && as.angle < -45);
                    errForPoint *= errForPoint;
//                    if (overflow) {
//                        errForPoint *= 2;
//                    }
                    if (errForPoint > maxSqrError) {
                        maxSqrError = errForPoint;
                    }
                }
//                as.sqrErr = err / angles.length;
                as.sqrErr = maxSqrError;
            }
//            if(angles.length==500) {
//                System.out.println();
//            }
            Arrays.sort(population);
            if (stopped) {

                System.out.println("thread " + angles.length + " stopped recycledItems " + recycledItems.size() + " best " + population[0] + " err " + Math.sqrt(population[0].sqrErr) + " gen " + generation);

                return;
            }

//            if (generation % 1000 == 0) {
//                System.out.println("points " + angles.length + " gen " + generation + " best " + population[0] + " err deg " + Math.sqrt(population[0].sqrErr));
//            }
            double errorSq = population[0].sqrErr;
            if (errorSq < 0.00000000003) {
                mutateK = 0.000000001;
            } else if (errorSq < 0.00000003) {
                mutateK = 0.0000001;
            } else if (errorSq < 0.0002) {
                mutateK = 0.0001;
            } else if (errorSq < 0.01) {
                mutateK = 0.001;
            } else if (errorSq < 1) {
                mutateK = 0.01;
            }
            if (population[0].sqrErr < bestReportedSqError) {
                if (bestReportedSqError <= 0 || population[0].sqrErr / bestReportedSqError < 0.8) {
                    bestReportedSqError = population[0].sqrErr;
//                    System.out.println("report err " + Math.sqrt(bestReportedSqError) + " points " + angles.length + " gen " + generation + " " + population[0]);
//                    if (Math.sqrt(bestReportedSqError) < 0.0001) {
//                        System.out.println("!!! found superb params in " + (System.nanoTime() - start) / 1000000 + " ms");
////                        System.exit(0);
//                    }
                    publishResults(population[0]);
                }
            }
            System.arraycopy(population, 0, newPopulation, 0, countSurvivors);
            for (int i = countSurvivors; i < countSurvivors + selectionCount; i++) {
                newPopulation[i] = mutate(recycledItems, population[random.nextInt(populationSize / 20)], mutateK);
            }
            for (int i = countSurvivors + selectionCount; i < newPopulation.length; i++) {
                newPopulation[i] = getRandom(recycledItems);
                if (initial != null && (i & 1) == 1) {
//                    population[i].B = initial.B;
                    population[i].C = initial.C;
                }
            }
            CalculatedAngleState[] oldPopulation = population;
            population = newPopulation;
            newPopulation = oldPopulation;
            for (CalculatedAngleState genericAngleState : oldPopulation) {
                if (genericAngleState != null) {
                    recycledItems.add(genericAngleState);
                }
            }
        }

//        CalculatedAngleState as = population[0];
//        as.setNewAngle(angles[0]);
//        as.setNewAngle(angles[1]);
//
//        for (int j = 2; j < angles.length - 1; j++) {
//            as.update(speeds[j], radiuses[j]);
//            System.out.printf("speed %8.2f a %8.4f preda %8.4f diff %8.4f\n", speeds[j], angles[j + 1], as.angle, angles[j + 1] - as.angle);
//        }
        System.out.println("thread " + angles.length + " ended recycledItems " + recycledItems.size() + " best " + population[0] + " err " + Math.sqrt(population[0].sqrErr) + " best reported error " + Math.sqrt(bestReportedSqError));
//        if (angles.length == 400 && Math.sqrt(bestReportedSqError) > 0.1
//                ) {
//            for (int j = 0; j < angles.length; j++) {
//                System.out.printf("speed %17.8f, r %17.8f, angle %17.8f,\n", speeds[j], radiuses[j], angles[j]);
//            }
//        }
//        if (Math.sqrt(bestReportedSqError) > 0.1) {
//            CalculatedAngleState as = population[0];
//            as.angleDer = angles[1] - angles[0];
//            as.angle = angles[1];
//            for (int j = 2; j < angles.length - 1; j++) {
//                as.update(speeds[j], radiuses[j]);
//                System.out.printf("angle %17.8f, speed %17.8f, radius %17.8f, preda %8.6f diff %8.6f\n", angles[j], speeds[j], radiuses[j] > 10000 ? 0 : radiuses[j], as.angle, angles[j + 1] - as.angle);
//            }
//        }


    }

    private CalculatedAngleState getRandom(ArrayList<CalculatedAngleState> recycledItems) {
        CalculatedAngleState as = getNew(recycledItems);
        as.minRAccell = random.nextDouble() * 0.4 + 0.1;
        as.A = random.nextDouble() * 0.6 + 0.2;
        as.B = 0.1;
        as.C = random.nextDouble() * 0.003;
        //as.E = random.nextDouble() * 0.3 - 0.15;
        return as;
    }

    private static CalculatedAngleState getNew(ArrayList<CalculatedAngleState> recycledItems) {
        CalculatedAngleState as = !recycledItems.isEmpty() ? recycledItems.remove(recycledItems.size() - 1) : new CalculatedAngleState();
        as.angle = as.angleDer = 0;
        return as;
    }

    public CalculatedAngleState mutate(ArrayList<CalculatedAngleState> recycledItems, CalculatedAngleState source, double mutateK) {
        CalculatedAngleState as = getNew(recycledItems);
        as.minRAccell = source.minRAccell * rnd(mutateK);
        as.A = source.A * rnd(mutateK);
//        as.B = source.B * rnd(mutateK);
        as.C = source.C * rnd(mutateK);
        //as.E = source.E * rnd(mutateK);
        as.angleDer = 0;
        return as;
    }


    private double rnd(double mutateK) {
        return 1 + (random.nextDouble() - 0.51) * mutateK;
    }


    private void publishResults(CalculatedAngleState angleState) {
        if (listener != null) {
            listener.onResultsFound(angles.length, angleState.minRAccell, angleState.A, angleState.B, angleState.C, Math.sqrt(angleState.sqrErr));
        }
    }

    public static class CalculatedAngleState implements Comparable<CalculatedAngleState> {
        double minRAccell;

        double A;
        double B;
        double C;
        //double E;

        public double angle;
        public double sqrErr;

        public double angleDer;

        public CalculatedAngleState() {
        }

        public void update(double speed, double radius) {
            double angleDer2 = -C * speed * angle - B * angleDer;
            double radiusUnsigned = Math.abs(radius);
            if (radiusUnsigned > 0.01) {
                double sqrtRadius = Math.sqrt(radiusUnsigned);
                if (speed > (minRAccell / A) * sqrtRadius) {
                    angleDer2 += Math.signum(radius) * (A / sqrtRadius * speed * speed - minRAccell * speed);
                }
            }
            angleDer += angleDer2;
            angle += angleDer;
        }

        @Override
        public int compareTo(CalculatedAngleState o) {
            return Double.compare(sqrErr, o.sqrErr);
        }

        @Override
        public String toString() {
            return "CalculatedAngleState{" +
                    "anglePredictorMinRAccell=" + minRAccell +
                    ", anglePredictorA=" + A +
//                    ", m=" + m +
//                    ", xi=" + xi +
//                    ", E=" + E +
//                    ", anglePredictorB=" + B +
                    ", anglePredictorC=" + C +
//                    ", R=" + R +
                    '}';
        }
    }
}
