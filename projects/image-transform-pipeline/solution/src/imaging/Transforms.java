package imaging;

public final class Transforms {
    private Transforms() {}

    public static Transform invert() {
        return in -> {
            int[] out = new int[in.pixels().length];
            for (int i = 0; i < out.length; i++) out[i] = 255 - in.pixels()[i];
            return new Image(in.name(), in.width(), in.height(), out);
        };
    }

    public static Transform brighten(int delta) {
        return in -> {
            int[] out = new int[in.pixels().length];
            for (int i = 0; i < out.length; i++) out[i] = Math.max(0, Math.min(255, in.pixels()[i] + delta));
            return new Image(in.name(), in.width(), in.height(), out);
        };
    }

    public static Transform flipHorizontal() {
        return in -> {
            int w = in.width();
            int[] out = new int[in.pixels().length];
            for (int y = 0; y < in.height(); y++) {
                for (int x = 0; x < w; x++) out[y * w + x] = in.pixels()[y * w + (w - 1 - x)];
            }
            return new Image(in.name(), w, in.height(), out);
        };
    }
}
