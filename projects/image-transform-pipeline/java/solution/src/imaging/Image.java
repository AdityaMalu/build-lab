package imaging;

public record Image(String name, int width, int height, int[] pixels) {

    public Image {
        if (width < 0 || height < 0 || pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException("pixels must have width*height entries");
        }
    }

    public long sizeBytes() {
        return (long) width * height * 4;
    }

    public int get(int x, int y) {
        return pixels[y * width + x];
    }
}
