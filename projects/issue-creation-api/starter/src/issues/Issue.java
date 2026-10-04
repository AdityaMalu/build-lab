package issues;

import java.util.LinkedHashMap;
import java.util.Map;

public record Issue(long id, String title, String body, String author, long version) {

    public Map<String, Object> toJsonMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("body", body);
        m.put("author", author);
        m.put("version", version);
        return m;
    }
}
