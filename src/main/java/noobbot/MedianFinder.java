package noobbot;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;

public final class MedianFinder {
    public final Queue<Double> minHeap = new PriorityQueue<>();
    public final Queue<Double> maxHeap = new PriorityQueue<>(100, new MaxHeapComparator());
    public int numOfElements;

    public void add(Double num) {
        maxHeap.add(num);
        if (numOfElements % 2 == 0) {
            if (minHeap.isEmpty()) {
                numOfElements++;
                return;
            } else if (maxHeap.peek() > minHeap.peek()) {
                Double maxHeapRoot = maxHeap.poll();
                Double minHeapRoot = minHeap.poll();
                maxHeap.add(minHeapRoot);
                minHeap.add(maxHeapRoot);
            }
        } else {
            minHeap.add(maxHeap.poll());
        }
        numOfElements++;
    }

    public double getMedian() {
        if (numOfElements % 2 != 0) {
            Double max = maxHeap.peek();
            return max == null ? 0 : max;
        } else {
            Double max = maxHeap.peek();
            Double min = minHeap.peek();
            return max == null || min == null ? 0 : (max + min) / 2;
        }
    }

    public int size() {
        return maxHeap.size();
    }

    private class MaxHeapComparator implements Comparator<Double> {
        @Override
        public int compare(Double o1, Double o2) {
            return o2 - o1 > 0 ? 1 : -1;
        }
    }

    @Override
    public String toString() {
        return Double.toString(getMedian());
    }
}
