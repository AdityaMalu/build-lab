package flags;

/** If user.attributes[attribute] equals equalsValue, serve variant. */
public record Rule(String attribute, String equalsValue, String variant) {
}
