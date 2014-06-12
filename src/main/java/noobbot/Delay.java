package noobbot;

import java.util.Arrays;

/**
 * Created by Valentin on 4/18/2014.
 */
public final class Delay {
    private final double[] queue;
    private int pos = 0;

    public Delay(int size) {
        this.queue = new double[size];
    }

    public Delay(Delay delay) {
        this.queue = new double[delay.queue.length];
        copyFrom(delay);
    }

    public double push(double value) {
        double oldValue = queue[pos];
        queue[pos] = value;
        pos++;
        if (pos == queue.length) {
            pos = 0;
        }
        return oldValue;
    }

    public void reset() {
        Arrays.fill(queue, 0);
    }

    public void copyFrom(Delay delay) {
        pos = delay.pos;
        System.arraycopy(delay.queue, 0, queue, 0, queue.length);
    }

    public double getDerivative() {
        if (queue.length >= 2) {
            //linear
            int p = pos;
            p--;
            if (p < 0) {
                p = queue.length - 1;
            }
            double derivative = queue[p];
            p--;
            if (p < 0) {
                p = queue.length - 1;
            }
            derivative -= queue[p];
            return derivative;
        } else {
            throw new IllegalArgumentException();
        }
    }

    public double secondDerivative() {
        if (queue.length >= 3) {
            int p = pos;
            p--;
            if (p < 0) {
                p = queue.length - 1;
            }
            double derivative = queue[p];
            p--;
            if (p < 0) {
                p = queue.length - 1;
            }
            derivative -= 2 * queue[p];
            p--;
            if (p < 0) {
                p = queue.length - 1;
            }
            return derivative + queue[p];
        } else {
            throw new IllegalArgumentException();
        }
    }


    public double getPrev() {
        int p = pos - 1;
        if (p < 0) {
            p = queue.length - 1;
        }
        p--;
        if (p < 0) {
            p = queue.length - 1;
        }
        return queue[p];
    }

    public boolean areAllSame() {
        if (queue.length <= 1) {
            return true;
        }
        double firstValue = queue[0];
        for (int i = 1; i < queue.length; i++) {
            if (Math.abs(queue[i] - firstValue) > 0.00001) {
                return false;
            }
        }
        return true;
    }

    public boolean haveZeroes() {
        for (double v : queue) {
            if(Math.abs(v) < 0.00001) {
                return true;
            }
        }
        return false;
    }
}
