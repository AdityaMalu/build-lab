package imaging;

@FunctionalInterface
public interface Transform {
    Image apply(Image in);
}
