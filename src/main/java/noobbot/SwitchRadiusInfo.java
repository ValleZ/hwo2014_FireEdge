package noobbot;

final class SwitchRadiusInfo implements Comparable<SwitchRadiusInfo> {
    final double r;
    final double pieceOffsRelative;

    public SwitchRadiusInfo(double r, double pieceOffsRelative) {
        this.r = r;
        this.pieceOffsRelative = pieceOffsRelative;
    }

    @Override
    public int compareTo(SwitchRadiusInfo o) {
        return Double.compare(pieceOffsRelative, o.pieceOffsRelative);
    }

    @Override
    public String toString() {
        return r + "," + pieceOffsRelative;
    }
}
