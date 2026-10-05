package imaging;

public record Result(String name, Image image, String error) {

    public static Result success(Image image) {
        return new Result(image.name(), image, null);
    }

    public static Result failure(String name, String error) {
        return new Result(name, null, error == null ? "error" : error);
    }

    public boolean ok() {
        return error == null;
    }
}
