package flags;

import java.util.Map;

public record User(String id, Map<String, String> attributes) {
}
