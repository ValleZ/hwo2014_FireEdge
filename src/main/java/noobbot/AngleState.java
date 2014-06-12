package noobbot;

public final class AngleState {
    public double angle;
    public double angleDer;
    private final Game game;

    public AngleState(Game game) {
        this.game = game;
    }

    public void update(double speed, double radius) {
        double angleDer2 = -game.anglePredictorC * speed * angle - game.anglePredictorB * angleDer;
        double radiusUnsigned = Math.abs(radius);
        if (radiusUnsigned < 100000000 && speed > (game.anglePredictorMinRAccell / game.anglePredictorA) * Math.sqrt(radiusUnsigned)) {
            angleDer2 += Math.signum(radius) * game.anglePredictorA / Math.sqrt(radiusUnsigned) * speed * speed - game.anglePredictorMinRAccell * speed * Math.signum(radius);
        }
        angleDer += angleDer2;
        angle += angleDer;
    }

    public void copy(AngleState angleState) {
        if (angleState != null) {
            angle = angleState.angle;
            angleDer = angleState.angleDer;
        }
    }
}
